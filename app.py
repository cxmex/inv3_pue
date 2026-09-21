import os
from dotenv import load_dotenv
load_dotenv()
import io
import uuid
import secrets
import asyncio
import urllib.parse
import requests
import httpx
import pytz
from datetime import datetime, timedelta
from typing import List, Optional


from fastapi import FastAPI, HTTPException, APIRouter, Request, Form, UploadFile, File
from fastapi.responses import StreamingResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from fastapi.templating import Jinja2Templates

templates = Jinja2Templates(directory="templates")

# PDF / QR
from reportlab.lib.pagesizes import letter, A4
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.lib.units import mm
from reportlab.graphics.barcode.qr import QrCodeWidget
from reportlab.graphics.shapes import Drawing
from reportlab.graphics import renderPDF
import qrcode

# ─────────────────────────────────────────────
# CONFIG
# ─────────────────────────────────────────────
SUPABASE_URL = os.getenv("SUPABASE_URL")
SUPABASE_KEY = os.getenv("SUPABASE_KEY")
LOCAL_CAMERA_SERVICE = "https://fred-nonchalky-fatally.ngrok-free.dev"

TELEGRAM_TOKEN = os.getenv("TELEGRAM_TOKEN", "")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", "")

HEADERS = {
    "apikey": SUPABASE_KEY,
    "Authorization": f"Bearer {SUPABASE_KEY}",
    "Content-Type": "application/json",
    "Prefer": "return=representation",
}

# Store identifier (terex3 = third store)
STORE_ID = "terex3"
INVENTORY_COL = "terex3"         # column in inventario1
VENTAS_TABLE = "ventas_terex3"   # sales table

# ── Backup / failover mode ──
IS_BACKUP = os.environ.get("IS_BACKUP", "").lower() in ("true", "1", "yes")

# ─────────────────────────────────────────────
# APP & ROUTER
# ─────────────────────────────────────────────
app = FastAPI(title="Nota Terex3")
router = APIRouter()


@app.get("/health")
async def health_check():
    """Health endpoint for failover proxy."""
    return {"status": "ok", "mode": "backup" if IS_BACKUP else "primary", "store": STORE_ID}


@app.get("/api/barcode-photo/{barcode}")
async def barcode_photo_telegram(barcode: str):
    """Send estilo/color photo to Telegram when barcode is scanned."""
    TG_TOKEN = "8487551934:AAGOw4FLIgXKolbeiFmAsRuyBS8mJ-3kSQk"
    TG_CHATS = ["7204722077", "7145539843", "8133878707"]
    try:
        inv = await supabase_request(
            method="GET", endpoint="/rest/v1/inventario1",
            params={"select": "name,estilo,estilo_id,color,color_id", "barcode": f"eq.{barcode}", "limit": "1"},
        )
        if not inv:
            return {"ok": False}

        item = inv[0]
        eid = item.get("estilo_id")
        color = item.get("color", "")
        color_id = item.get("color_id")
        estilo = item.get("estilo", "")
        if not eid:
            return {"ok": False}

        # Resolve color_id from name
        if not color_id and color:
            try:
                cr = await supabase_request(
                    method="GET",
                    endpoint=f"/rest/v1/inventario1?color=eq.{color}&color_id=not.is.null&select=color_id&limit=1",
                ) or []
                if cr: color_id = cr[0]["color_id"]
            except: pass

        photo_url = None

        # 1. Color photo from image_uploads
        if color_id:
            try:
                rows = await supabase_request(
                    method="GET",
                    endpoint=f"/rest/v1/image_uploads?estilo_id=eq.{eid}&color_id=eq.{color_id}&select=public_url&limit=1",
                ) or []
                if rows and rows[0].get("public_url"):
                    photo_url = rows[0]["public_url"]
            except: pass

        # 2. Estilo photo from bucket
        if not photo_url:
            try:
                import requests as req
                resp = req.post(
                    f"{SUPABASE_URL}/storage/v1/object/list/images_estilos",
                    headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}", "Content-Type": "application/json"},
                    json={"prefix": f"{eid}/", "limit": 1}, timeout=6,
                )
                if resp.status_code < 400:
                    files = [f for f in resp.json() if f.get("id")]
                    if files:
                        photo_url = f"{SUPABASE_URL}/storage/v1/object/public/images_estilos/{eid}/{files[0]['name']}"
            except: pass

        # 3. Send
        if photo_url:
            caption = f"📸 {estilo}\n🎨 {color}\n📦 {barcode}\n🏪 Sucursal 3"
            import requests as req
            for chat_id in TG_CHATS:
                try:
                    req.post(f"https://api.telegram.org/bot{TG_TOKEN}/sendPhoto",
                        json={"chat_id": chat_id, "photo": photo_url, "caption": caption}, timeout=5)
                except: pass
            return {"ok": True, "photo_url": photo_url}
        else:
            msg = (f"📷 RECORDAR QUE ESTE ESTILO/COLOR NO TIENE FOTO "
                   f"Y SE LE TIENE QUE DAR A EMANUEL\n\n"
                   f"Estilo: {estilo} (id={eid})\nColor: {color}\nCodigo: {barcode}\n🏪 Sucursal 3")
            import requests as req
            for chat_id in TG_CHATS:
                try:
                    req.post(f"https://api.telegram.org/bot{TG_TOKEN}/sendMessage",
                        json={"chat_id": chat_id, "text": msg}, timeout=5)
                except: pass
            return {"ok": True, "photo_url": None}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ─────────────────────────────────────────────
# DATA MODELS
# ─────────────────────────────────────────────
class ProductItem(BaseModel):
    qty: int = 1
    name: str = ""
    codigo: str = ""
    price: float = 0.0
    customer_email: Optional[str] = None


class SavePayload(BaseModel):
    products: List[ProductItem]
    payment_method: str = "efectivo"
    idempotency_key: Optional[str] = None



class ConteoEfectivoResponse(BaseModel):
    id: int
    nombre: str
    tipo: str
    amount: float
    balance: float
    created_at: str
    order_id: Optional[int] = None
    descripcion: Optional[str] = None
    diferencia: Optional[float] = None

class ConteoEfectivoCreate(BaseModel):
    nombre: str
    tipo: str
    amount: float

class TransferenciaRow(BaseModel):
    id: int
    created_at: Optional[str] = None
    fecha: Optional[str] = None
    hora: Optional[str] = None
    order_id: Optional[int] = None
    name: Optional[str] = None
    name_id: Optional[str] = None
    estilo: Optional[str] = None
    estilo_id: Optional[int] = None
    modelo: Optional[str] = None
    modelo_id: Optional[int] = None
    cost: Optional[float] = None
    price: Optional[float] = None
    subtotal: Optional[float] = None
    total: Optional[float] = None
    cliente: Optional[str] = None
    id_cliente: Optional[int] = None
    whatsapp: Optional[str] = None
    qty: Optional[int] = None
    marca: Optional[str] = None
    color: Optional[str] = None
    payment_method: Optional[str] = None

# ─────────────────────────────────────────────
# SUPABASE HELPER
# ─────────────────────────────────────────────
async def supabase_request(
    method: str,
    endpoint: str,
    params: dict = None,
    json_data: dict = None,
) -> list | dict | None:
    url = f"{SUPABASE_URL}{endpoint}"
    try:
        import httpx
        async with httpx.AsyncClient() as client:
            resp = await client.request(
                method.upper(), url, headers=HEADERS, params=params, json=json_data, timeout=15
            )
        resp.raise_for_status()
        if resp.content:
            return resp.json()
        return None
    except httpx.HTTPStatusError as e:
        print(f"Supabase HTTP error [{method} {endpoint}]: {e} — {e.response.text}")
        raise
    except Exception as e:
        print(f"Supabase request error: {e}")
        raise


async def _log_inv_change(barcode, product_name, branch, source, qty_before, qty_after, reference_id=None, notes=None):
    try:
        await supabase_request(
            method="POST",
            endpoint="/rest/v1/inventory_changes",
            json_data={
                "barcode":      int(barcode),
                "product_name": product_name or "",
                "branch":       branch,
                "source":       source,
                "qty_before":   int(qty_before),
                "qty_after":    int(qty_after),
                "delta":        int(qty_after) - int(qty_before),
                "reference_id": str(reference_id) if reference_id else None,
                "notes":        notes,
            }
        )
    except Exception as e:
        print(f"[INV_CHANGE] log failed barcode={barcode} source={source}: {e}")


# ─────────────────────────────────────────────
# ORDER ID
# ─────────────────────────────────────────────
async def get_next_order_id() -> int:
    rows = await supabase_request(
        method="GET",
        endpoint=f"/rest/v1/{VENTAS_TABLE}",
        params={"select": "order_id", "order": "order_id.desc", "limit": "1"},
    )
    if rows:
        return (rows[0].get("order_id") or 0) + 1
    return 1


# ─────────────────────────────────────────────
# CASH BALANCE
# ─────────────────────────────────────────────
async def get_current_balance() -> float:
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/conteo_efectivo",
        params={"select": "balance", "order": "id.desc", "limit": "1"},
    )
    if rows:
        return float(rows[0].get("balance") or 0)
    return 0.0


# ─────────────────────────────────────────────
# LOYALTY
# ─────────────────────────────────────────────
async def process_loyalty_deduction(p_dict: dict, order_id: int, fecha: str, hora: str) -> dict:
    """Deduct loyalty points and log the transaction."""
    codigo = p_dict.get("codigo", "")
    amount = abs(float(p_dict.get("price", 0)))
    customer_email = p_dict.get("customer_email", "")

    # Find customer by barcode
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/loyalty_customers",
        params={"select": "id,email,balance", "barcode": f"eq.{codigo}", "limit": "1"},
    )
    if not rows:
        print(f"Loyalty barcode {codigo} not found — skipping deduction")
        return {"status": "not_found", "codigo": codigo}

    customer = rows[0]
    current_balance = float(customer.get("balance") or 0)
    new_balance = max(0, current_balance - amount)

    # Update balance
    await supabase_request(
        method="PATCH",
        endpoint=f"/rest/v1/loyalty_customers?id=eq.{customer['id']}",
        json_data={"balance": new_balance},
    )

    # Log deduction
    await supabase_request(
        method="POST",
        endpoint="/rest/v1/loyalty_transactions",
        json_data={
            "customer_id": customer["id"],
            "barcode": codigo,
            "amount": -amount,
            "balance_after": new_balance,
            "order_id": order_id,
            "fecha": fecha,
            "hora": hora,
            "store": STORE_ID,
        },
    )

    return {
        "status": "ok",
        "codigo": codigo,
        "email": customer.get("email", customer_email),
        "deducted": amount,
        "new_balance": new_balance,
    }

# (Obsolete duplicate handlers removed — router version below handles CLIENTE + 9000 + 8000)


# ─────────────────────────────────────────────
# REDEMPTION TOKEN
# ─────────────────────────────────────────────
def generate_redemption_token() -> str:
    return secrets.token_urlsafe(16)


async def store_redemption_token(order_id: int, token: str, total: float):
    try:
        await supabase_request(
            method="POST",
            endpoint="/rest/v1/redemption_tokens",
            json_data={
                "order_id": order_id,
                "token": token,
                "total": total,
                "store": STORE_ID,
                "used": False,
            },
        )
    except Exception as e:
        print(f"Could not store redemption token: {e}")


# ─────────────────────────────────────────────
# QR REWARDS (WhatsApp loyalty - 1% on next purchase)
# ─────────────────────────────────────────────
async def store_qr_reward(order_id: int, token: str, purchase_amount: float) -> None:
    """Insert a row into qr_rewards so the token can be redeemed later via WhatsApp."""
    reward_amount = round(purchase_amount * 0.01, 2)
    try:
        await supabase_request(
            method="POST",
            endpoint="/rest/v1/qr_rewards",
            json_data={
                "qr_token": token,
                "order_id": order_id,
                "purchase_amount": purchase_amount,
                "reward_amount": reward_amount,
                "status": "pending",
            },
        )
        print(f"QR reward stored: order={order_id} reward=${reward_amount}", flush=True)
    except Exception as e:
        print(f"ERROR storing qr_reward: {e}", flush=True)


# ─────────────────────────────────────────────
# CUSTOMER BARCODE HANDLING (POS redemption of WhatsApp loyalty)
# ─────────────────────────────────────────────
async def handle_customer_qr(phone: str):
    """Look up all 'linked' qr_rewards for this phone, return aggregated credit as a loyalty row."""
    try:
        rewards = await supabase_request(
            method="GET",
            endpoint="/rest/v1/qr_rewards",
            params={
                "select": "id,reward_amount",
                "phone_number": f"eq.{phone}",
                "status": "eq.linked",
            },
        )
        if not rewards:
            raise HTTPException(
                status_code=404,
                detail=f"Cliente {phone} no tiene creditos disponibles.",
            )
        total_credit = round(sum(float(r["reward_amount"]) for r in rewards), 2)
        ids = [r["id"] for r in rewards]
        return {
            "name": f"CREDITO CLIENTE ({phone})",
            "price": -total_credit,
            "codigo": f"CLIENTE:{phone}",
            "is_loyalty": True,
            "customer_phone": phone,
            "qr_reward_ids": ids,
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error looking up customer QR: {e}", flush=True)
        raise HTTPException(status_code=500, detail=str(e))


async def handle_customer_barcode_scan(barcode: str):
    """POS scanned a 13-digit customer barcode (9000...). Resolve to phone then fetch credits."""
    try:
        rows = await supabase_request(
            method="GET",
            endpoint="/rest/v1/customers",
            params={"customer_barcode": f"eq.{barcode}", "select": "id,phone_number", "limit": "1"},
        )
        if not rows:
            raise HTTPException(status_code=404, detail=f"Cliente con barcode {barcode} no encontrado")
        phone = rows[0]["phone_number"]
        return await handle_customer_qr(phone)
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error looking up customer by barcode: {e}", flush=True)
        raise HTTPException(status_code=500, detail=str(e))


async def process_cliente_redemption(product_dict: dict, order_id: int) -> None:
    """Mark all of the customer's 'linked' qr_rewards as 'redeemed' now that they are used in this order."""
    codigo = product_dict.get("codigo", "")
    if not codigo.upper().startswith("CLIENTE:"):
        return
    phone = codigo.split(":", 1)[1].strip()
    now_iso = datetime.utcnow().isoformat()

    try:
        rewards = await supabase_request(
            method="GET",
            endpoint="/rest/v1/qr_rewards",
            params={
                "select": "id",
                "phone_number": f"eq.{phone}",
                "status": "eq.linked",
            },
        )
        for r in rewards:
            rid = r.get("id")
            await supabase_request(
                method="PATCH",
                endpoint=f"/rest/v1/qr_rewards?id=eq.{rid}",
                json_data={
                    "status": "redeemed",
                    "redeemed_at": now_iso,
                    "redeemed_order_id": order_id,
                },
            )
        print(f"Redeemed {len(rewards) if rewards else 0} qr_rewards for phone {phone} -> order {order_id}", flush=True)
    except Exception as e:
        print(f"ERROR redeeming customer credits: {e}", flush=True)


# ─────────────────────────────────────────────
# TELEGRAM
# ─────────────────────────────────────────────
def send_telegram_message(text: str):
    if not TELEGRAM_TOKEN or not TELEGRAM_CHAT_ID:
        return
    try:
        url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"
        requests.post(url, json={"chat_id": TELEGRAM_CHAT_ID, "text": text}, timeout=5)
    except Exception as e:
        print(f"Telegram message error: {e}")


async def send_telegram_picture(barcode: str = None, order_id: int = None):
    """Forward a captured camera image for the order to Telegram."""
    if not TELEGRAM_TOKEN or not TELEGRAM_CHAT_ID:
        return
    try:
        img_url = f"{LOCAL_CAMERA_SERVICE}/api/last_capture"
        if order_id:
            img_url += f"?order_id={order_id}"
        img_resp = requests.get(img_url, timeout=5)
        if img_resp.status_code == 200:
            tg_url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendPhoto"
            requests.post(
                tg_url,
                data={"chat_id": TELEGRAM_CHAT_ID, "caption": f"Venta #{order_id} — {STORE_ID}"},
                files={"photo": ("capture.jpg", img_resp.content, "image/jpeg")},
                timeout=10,
            )
    except Exception as e:
        print(f"Telegram picture error: {e}")


# ─────────────────────────────────────────────
# TICKET PDF STORAGE
# ─────────────────────────────────────────────
TICKET_BUCKET = "tickets"


async def upload_ticket_to_storage(order_id: int, pdf_bytes: bytes):
    """Upload ticket PDF to Supabase Storage bucket 'tickets'."""
    storage_path = f"{order_id}.pdf"
    storage_headers = {
        "apikey": SUPABASE_KEY,
        "Authorization": f"Bearer {SUPABASE_KEY}",
    }
    try:
        import httpx as _hx
        async with _hx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{SUPABASE_URL}/storage/v1/object/{TICKET_BUCKET}/{storage_path}",
                headers={**storage_headers, "Content-Type": "application/pdf"},
                content=pdf_bytes,
            )
            if resp.status_code < 400:
                print(f"Ticket PDF uploaded: {order_id}.pdf", flush=True)
            else:
                print(f"Ticket PDF upload failed: {resp.status_code} {resp.text[:200]}", flush=True)
    except Exception as e:
        print(f"Ticket PDF upload error: {e}", flush=True)


# ─────────────────────────────────────────────
# PDF RECEIPT WITH QR
# ─────────────────────────────────────────────
def _build_receipt_pdf_with_qr(
    items: list, total: float, order_id: int, redemption_token: str,
    show_items: bool = False
) -> io.BytesIO:
    """PDF receipt for 80mm thermal printer.
    show_items=False  → summary-only (for printing at POS)
    show_items=True   → full item detail (sent via WhatsApp after QR scan)
    """
    width = 80 * mm
    margin = 3 * mm

    total_pieces = sum(int(it.get("qty", 0)) for it in items)
    reward_amount = round(total * 0.01, 2)

    # Height: fixed for summary, dynamic for detailed
    if show_items:
        item_h = 9 * mm
        height = 28 * mm + (len(items) * item_h) + 20 * mm + 30 * mm + 2 * margin
    else:
        height = 165 * mm

    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=(width, height))
    y = height - margin

    # ── Header ────────────────────────────────────────────────────────────
    c.setFont("Helvetica-Bold", 14)
    c.drawCentredString(width / 2, y, "TEREX3")
    y -= 14

    c.setFont("Helvetica-Bold", 11)
    c.drawCentredString(width / 2, y, f"Ticket #{order_id}")
    y -= 13

    mexico_tz = pytz.timezone("America/Mexico_City")
    now = datetime.now(mexico_tz)
    fecha = now.strftime("%Y-%m-%d")
    hora = now.strftime("%H:%M:%S")
    c.setFont("Helvetica", 9)
    c.drawCentredString(width / 2, y, f"{fecha}  {hora}")
    y -= 10

    c.setLineWidth(0.5)
    c.line(margin, y, width - margin, y)
    y -= 12

    # ── Items (only when show_items=True) ─────────────────────────────────
    if show_items:
        c.setFont("Helvetica-Bold", 9)
        c.drawString(margin, y, "Producto")
        c.drawRightString(width - margin, y, "Subtotal")
        y -= 10
        c.line(margin, y + 4, width - margin, y + 4)

        c.setFont("Helvetica", 9)
        for it in items:
            qty = int(it.get("qty", 0))
            name = str(it.get("name", ""))
            price = float(it.get("price", 0) or 0)
            sub = float(it.get("subtotal", qty * price) or 0)
            display_name = name if len(name) <= 32 else name[:31] + "…"

            c.setFont("Helvetica", 9)
            c.drawString(margin, y, f"{qty}x  {display_name}")
            y -= 10

            c.setFont("Helvetica", 8)
            c.setFillColor(colors.grey)
            c.drawString(margin + 10, y, f"@ ${price:0.2f} c/u")
            c.setFillColor(colors.black)
            c.drawRightString(width - margin, y, f"${sub:0.2f}")
            y -= 12

        c.setLineWidth(0.5)
        c.line(margin, y + 2, width - margin, y + 2)
        y -= 6

    # ── Totals ────────────────────────────────────────────────────────────
    c.setFont("Helvetica", 10)
    c.drawString(margin, y, "Total piezas:")
    c.drawRightString(width - margin, y, f"{total_pieces}")
    y -= 13

    c.setFont("Helvetica-Bold", 13)
    c.drawString(margin, y, "TOTAL:")
    c.drawRightString(width - margin, y, f"${total:0.2f}")
    y -= 16

    if not show_items:
        # ── QR + Loyalty (only on printed summary) ────────────────────────
        c.setStrokeColor(colors.black)
        c.setDash(1, 2)
        c.line(margin, y, width - margin, y)
        c.setDash()
        y -= 12

        c.setFont("Helvetica-Bold", 9)
        c.drawCentredString(width / 2, y, "ESCANEA ESTE QR CODE Y OBTEN")
        y -= 10
        c.drawCentredString(width / 2, y, "1% PARA TU SIGUIENTE COMPRA")
        y -= 10

        c.setFont("Helvetica", 8)
        c.setFillColor(colors.grey)
        c.drawCentredString(width / 2, y, f"Credito a obtener: ${reward_amount:0.2f}")
        c.setFillColor(colors.black)
        y -= 12

        business_phone = os.environ.get("WHATSAPP_BUSINESS_NUMBER", "525642460019")
        prefilled = urllib.parse.quote(f"CANJEAR:{redemption_token}")
        qr_url = f"https://wa.me/{business_phone}?text={prefilled}"

        try:
            qr_size = 40 * mm
            qr_widget = QrCodeWidget(qr_url)
            qr_widget.barWidth = qr_size
            qr_widget.barHeight = qr_size
            qr_drawing = Drawing(qr_size, qr_size)
            qr_drawing.add(qr_widget)
            x_qr = (width - qr_size) / 2
            y_qr = y - qr_size
            renderPDF.draw(qr_drawing, c, x_qr, y_qr)
            y = y_qr - 8
        except Exception as e:
            print(f"QR error: {e}", flush=True)
            c.setFont("Helvetica", 7)
            c.drawCentredString(width / 2, y - 10, f"Token: {redemption_token[:20]}...")
            y -= 20

        c.setFont("Helvetica", 7)
        c.setFillColor(colors.grey)
        c.drawCentredString(width / 2, y, "TAMBIEN PODRA VER EL DETALLE DE SU")
        y -= 8
        c.drawCentredString(width / 2, y, "COMPRA, UNA VEZ ESCANEADO EL")
        y -= 8
        c.drawCentredString(width / 2, y, "CODIGO QR EN SU WHATSAPP")
        y -= 10

    c.setFont("Helvetica", 7)
    c.setFillColor(colors.grey)
    c.drawCentredString(width / 2, y, "¡Gracias por su compra!")
    c.setFillColor(colors.black)

    c.showPage()
    c.save()
    buf.seek(0)
    return buf

# ─────────────────────────────────────────────
# ROUTES
# ─────────────────────────────────────────────

@router.get("/health")
async def health():
    return {"status": "ok", "store": STORE_ID}


@router.get("/api/sync_prices")
async def sync_prices():
    """Refresh local price cache from Supabase (returns count of rows)."""
    try:
        rows = await supabase_request(
            method="GET",
            endpoint="/rest/v1/inventario1",
            params={"select": "barcode,name,precio", "limit": "10000"},
        )
        return {"updated": len(rows) if rows else 0}
    except Exception as e:
        return {"updated": 0, "error": str(e)}


@router.get("/api/search_barcode")
async def search_barcode(barcode: str):
    """
    Returns product data for a barcode.
    - CLIENTE:<phone>  → WhatsApp customer credit (text-form)
    - 9000... 13-digit → WhatsApp customer barcode
    - 8000... 13-digit → legacy loyalty card
    """
    if not barcode:
        raise HTTPException(status_code=400, detail="Barcode requerido")

    # ── Customer text code (from CLIENTE: prefix) ─────────────────────────
    if barcode.upper().startswith("CLIENTE:"):
        phone = barcode.split(":", 1)[1].strip()
        return await handle_customer_qr(phone)

    # ── Customer barcode (WhatsApp-generated Code128/EAN-13) ──────────────
    if barcode.startswith("9000") and len(barcode) == 13 and barcode.isdigit():
        return await handle_customer_barcode_scan(barcode)

    # ── Loyalty card ──────────────────────────────────────────────────────
    if barcode.startswith("8000") and len(barcode) == 13:
        rows = await supabase_request(
            method="GET",
            endpoint="/rest/v1/loyalty_customers",
            params={"select": "email,balance", "barcode": f"eq.{barcode}", "limit": "1"},
        )
        if not rows:
            raise HTTPException(status_code=404, detail="Cliente no encontrado")
        customer = rows[0]
        balance = float(customer.get("balance") or 0)
        return {
            "name": f"Saldo lealtad ({customer.get('email', '')})",
            "codigo": barcode,
            "price": -balance,          # negative → discount row
            "is_loyalty": True,
            "customer_email": customer.get("email", ""),
        }

    # ── Regular product ───────────────────────────────────────────────────
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario1",
        params={
            "select": f"barcode,name,estilo,precio,terex1,terex3",
            "barcode": f"eq.{barcode}",
            "limit": "1",
        },
    )
    if not rows:
        raise HTTPException(status_code=404, detail=f"Producto {barcode} no encontrado")

    product = rows[0]
    # Allow sale even when stock is 0 or negative (inventory reconciliation is done manually)
    return {
        "name": product.get("name", ""),
        "codigo": barcode,
        "estilo": product.get("estilo", ""),
        "price": float(product.get("precio") or 0),
        "terex1": int(product.get("terex1") or 0),
        "terex3": int(product.get("terex3") or 0),
        "is_loyalty": False,
    }


@router.post("/api/start_camera_capture")
async def start_camera_capture(barcode: str = ""):
    """Forward camera capture request to local camera service."""
    try:
        resp = requests.post(
            f"{LOCAL_CAMERA_SERVICE}/capture",
            params={"barcode": barcode},
            timeout=3,
        )
        return {"status": "ok", "camera_status": resp.status_code}
    except Exception as e:
        print(f"Camera capture error: {e}")
        return {"status": "error", "detail": str(e)}


_recent_sales_keys: dict[str, float] = {}  # idempotency_key → timestamp

@router.post("/api/save")
async def api_save(payload: SavePayload):
    """Process and persist a sale for store terex3."""
    if not payload.products:
        raise HTTPException(status_code=400, detail="No products provided")

    # ── Duplicate submission guard ─────────────────────────────────────
    import time
    now_ts = time.time()
    stale = [k for k, t in _recent_sales_keys.items() if now_ts - t > 60]
    for k in stale:
        _recent_sales_keys.pop(k, None)

    idem_key = payload.idempotency_key
    if not idem_key:
        parts = sorted(
            f"{p.codigo}-{p.qty}-{p.price}" for p in payload.products
        )
        idem_key = "|".join(parts)

    if idem_key in _recent_sales_keys:
        elapsed = now_ts - _recent_sales_keys[idem_key]
        print(f"⚠️ Duplicate sale blocked (key={idem_key}, {elapsed:.1f}s ago)")
        raise HTTPException(
            status_code=409,
            detail="Venta duplicada detectada — esta venta ya se guardó hace unos segundos"
        )
    _recent_sales_keys[idem_key] = now_ts

    payment_method = payload.payment_method or "efectivo"
    print(f"DEBUG: payment_method={payment_method}")

    next_order_id = await get_next_order_id()

    mexico_tz = pytz.timezone("America/Mexico_City")
    now = datetime.now(mexico_tz)
    fecha = now.strftime("%Y-%m-%d")
    hora = now.strftime("%H:%M:%S")

    items_for_ticket: list = []
    loyalty_deductions: list = []

    for p in payload.products:
        p_dict = p.model_dump() if hasattr(p, "model_dump") else p.dict()
        codigo = p_dict.get("codigo", "")

        # ── Customer WhatsApp credit redemption (CLIENTE: code) ──────────
        if codigo.upper().startswith("CLIENTE:"):
            await process_cliente_redemption(p_dict, next_order_id)
            items_for_ticket.append({
                "qty": p_dict.get("qty", 1),
                "name": p_dict.get("name", ""),
                "price": p_dict.get("price", 0),
                "subtotal": p_dict.get("qty", 1) * p_dict.get("price", 0),
            })
            continue

        # ── Loyalty redemption ───────────────────────────────────────────
        if codigo.startswith("8000") and len(codigo) == 13:
            result = await process_loyalty_deduction(p_dict, next_order_id, fecha, hora)
            loyalty_deductions.append(result)
            items_for_ticket.append({
                "qty": p_dict.get("qty", 1),
                "name": p_dict.get("name", ""),
                "price": p_dict.get("price", 0),
                "subtotal": p_dict.get("qty", 1) * p_dict.get("price", 0),
            })
            continue

        # ── Regular product ──────────────────────────────────────────────
        inv_rows = await supabase_request(
            method="GET",
            endpoint="/rest/v1/inventario1",
            params={
                "select": f"modelo,modelo_id,estilo,estilo_id,{INVENTORY_COL},precio",
                "barcode": f"eq.{codigo}",
                "limit": "1",
            },
        )
        if not inv_rows:
            raise HTTPException(
                status_code=400,
                detail=f"Producto con barcode {codigo} no existe en inventario1",
            )
        inv = inv_rows[0]

        # ── $0 price guard: never sell at $0 ─────────────────────────────
        sale_price = p_dict.get("price", 0) or 0
        if sale_price <= 0:
            catalog_price = inv.get("precio") or 0
            if catalog_price > 0:
                sale_price = catalog_price
                print(f"🚨 $0 price corrected → ${catalog_price} for {p_dict.get('name','')} ({codigo})")
            else:
                sale_price = 90
                try:
                    await supabase_request(
                        method="PATCH",
                        endpoint=f"/rest/v1/inventario1?barcode=eq.{codigo}",
                        json_data={"precio": 90},
                    )
                except Exception:
                    pass
                print(f"🚨 $0 price with no catalog price — defaulted to $90 for {p_dict.get('name','')} ({codigo})")
            try:
                send_telegram_message(
                    f"🚨 ARGOS · Venta a $0 corregida (S3)\n"
                    f"Producto: {p_dict.get('name', '?')}\n"
                    f"Código: {codigo}\n"
                    f"Precio aplicado: ${sale_price}"
                )
            except Exception:
                pass

        # Insert sale record
        record = {
            "qty": p_dict.get("qty", 1),
            "name": p_dict.get("name", ""),
            "name_id": codigo,
            "price": sale_price,
            "fecha": fecha,
            "hora": hora,
            "order_id": next_order_id,
            "modelo": inv.get("modelo", ""),
            "modelo_id": inv.get("modelo_id", ""),
            "estilo": inv.get("estilo", ""),
            "estilo_id": inv.get("estilo_id", ""),
            "payment_method": payment_method,
        }
        await supabase_request(
            method="POST",
            endpoint=f"/rest/v1/{VENTAS_TABLE}",
            json_data=record,
        )

        # Decrement inventory
        current_qty = int(inv.get(INVENTORY_COL) or 0)
        new_qty = current_qty - p_dict.get("qty", 1)
        await supabase_request(
            method="PATCH",
            endpoint=f"/rest/v1/inventario1?barcode=eq.{codigo}",
            json_data={INVENTORY_COL: new_qty},
        )

        items_for_ticket.append({
            "qty": p_dict.get("qty", 1),
            "name": p_dict.get("name", ""),
            "price": sale_price,
            "subtotal": p_dict.get("qty", 1) * sale_price,
        })

    total = sum(i["subtotal"] for i in items_for_ticket)

    # ── Telegram notification ────────────────────────────────────────────
    try:
        payment_emoji = "💵" if payment_method == "efectivo" else "💳"
        total_pieces = sum(i["qty"] for i in items_for_ticket)
        send_telegram_message(
            f"🎉 VENTA #{next_order_id} [{STORE_ID.upper()}]\n"
            f"📊 {total_pieces} piezas\n"
            f"💰 ${total:.2f}\n"
            f"{payment_emoji} {payment_method.title()}"
        )
        import asyncio
        asyncio.create_task(send_telegram_picture(order_id=next_order_id))
    except Exception as e:
        print(f"Telegram error: {e}")

    # ── Cash register ────────────────────────────────────────────────────
    if payment_method == "efectivo":
        try:
            current_balance = await get_current_balance3()
            new_balance = current_balance + total
            conteo_payload = {
                "nombre": f"Venta #{next_order_id} [terex3]",
                "tipo": "credito",
                "amount": total,
                "balance": new_balance,
                "order_id": next_order_id,
            }
            await supabase_request(method="POST", endpoint="/rest/v1/conteo_efectivo3", json_data=conteo_payload)
            print(f"Cash entry added for order {next_order_id}: ${total}")
        except Exception as e:
            print(f"Error adding conteo_efectivo entry: {e}")
    else:
        print(f"DEBUG: Skipping conteo_efectivo (payment_method={payment_method})")

    # ── Redemption token & PDF ───────────────────────────────────────────
    redemption_token = generate_redemption_token()
    await store_redemption_token(next_order_id, redemption_token, total)
    # Also store in qr_rewards for the WhatsApp loyalty flow
    await store_qr_reward(next_order_id, redemption_token, total)
    pdf_buf = _build_receipt_pdf_with_qr(items_for_ticket, total, next_order_id, redemption_token)

    # Upload a copy to the "tickets" storage bucket (non-blocking)
    pdf_bytes = pdf_buf.read()
    import asyncio as _asyncio
    _asyncio.create_task(upload_ticket_to_storage(next_order_id, pdf_bytes))

    filename = f"ticket_{STORE_ID}_{next_order_id}_{int(datetime.now().timestamp()*1000)}.pdf"
    return StreamingResponse(
        io.BytesIO(pdf_bytes),
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# ─────────────────────────────────────────────
# SERVE FRONTEND
# ─────────────────────────────────────────────
@router.get("/", response_class=HTMLResponse)
async def serve_frontend():
    try:
        with open("static/index.html", "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    except FileNotFoundError:
        return HTMLResponse(content="<h1>Frontend not found — place index.html in /static</h1>")


@app.get("/nota1", response_class=HTMLResponse)
async def nota(request: Request):
    return templates.TemplateResponse(request=request, name="nota1.html", context={})



@app.get("/entradamercancia3", response_class=HTMLResponse)
async def get_entrada_mercancia_3_form(request: Request):
    """Render the merchandise entry form for store 2"""
    try:
        print("Loading entrada mercancia 2 form", flush=True)
        return templates.TemplateResponse(request=request, name="entrada_mercancia_3.html", context={})
    except Exception as e:
        print(f"Error loading entrada mercancia 2 form: {str(e)}", flush=True)
        raise HTTPException(status_code=500, detail=f"Error loading form: {str(e)}")


@app.post("/entradamercancia3")
async def process_entrada_mercancia_3(
    request: Request,
    qty: int = Form(...),
    barcode: str = Form(...),
    conteo_previo_caja: Optional[int] = Form(None)
):
    """Process merchandise entry form and save to entrada_mercancia_3 / update terex3"""
    try:
        print(f"Processing entrada mercancia 2: qty={qty}, barcode={barcode}", flush=True)

        if qty <= 0:
            raise HTTPException(status_code=400, detail="La cantidad debe ser mayor a 0")

        if not barcode or barcode.strip() == "":
            raise HTTPException(status_code=400, detail="El código de barras es requerido")

        barcode = barcode.strip()

        try:
            barcode_int = int(barcode)
        except ValueError:
            raise HTTPException(status_code=400, detail="El código de barras debe ser numérico")

        # Fetch product info and current terex3 stock
        product_info = None
        current_terex3 = 0
        try:
            product_response = await supabase_request(
                method="GET",
                endpoint="/rest/v1/inventario1",
                params={
                    "select": "name,estilo_id,marca,terex3",
                    "barcode": f"eq.{barcode}",
                    "limit": "1"
                }
            )
            if product_response and len(product_response) > 0:
                product_info = product_response[0]
                current_terex3 = product_info.get("terex3", 0) or 0
                print(f"Found product: {product_info}, current terex3: {current_terex3}", flush=True)
            else:
                print(f"No product found with barcode {barcode}", flush=True)
        except Exception as product_error:
            print(f"Error fetching product info: {str(product_error)}", flush=True)

        # Build insert payload
        entrada_data = {
            "qty": qty,
            "barcode": barcode_int,
        }
        if product_info:
            if product_info.get("name"):
                entrada_data["estilo"] = product_info.get("name", "")
            if product_info.get("estilo_id"):
                entrada_data["estilo_id"] = product_info.get("estilo_id")
        if conteo_previo_caja is not None:
            entrada_data["conteo_previo_caja"] = conteo_previo_caja

        print(f"Inserting entrada_mercancia_3 data: {entrada_data}", flush=True)

        # Insert into entrada_mercancia_3
        entrada_success = False
        try:
            response = await supabase_request(
                method="POST",
                endpoint="/rest/v1/entrada_mercancia_3",
                json_data=entrada_data
            )
            print(f"Insert response: {response}", flush=True)
            entrada_success = True
        except Exception as insert_error:
            print(f"Insert error: {str(insert_error)}, trying minimal insert", flush=True)
            try:
                response = await supabase_request(
                    method="POST",
                    endpoint="/rest/v1/entrada_mercancia_3",
                    json_data={"qty": qty, "barcode": barcode_int}
                )
                print(f"Minimal insert successful: {response}", flush=True)
                entrada_success = True
            except Exception as minimal_error:
                print(f"Minimal insert failed: {str(minimal_error)}", flush=True)
                raise HTTPException(status_code=500, detail=f"Database error: {str(minimal_error)}")

        # Update terex3 in inventario1
        if entrada_success and product_info:
            try:
                new_terex3 = current_terex3 + qty
                print(f"Updating terex3: {current_terex3} → {new_terex3} for barcode {barcode}", flush=True)
                update_response = await supabase_request(
                    method="PATCH",
                    endpoint=f"/rest/v1/inventario1?barcode=eq.{barcode_int}",
                    json_data={"terex3": new_terex3}
                )
                print(f"terex3 update response: {update_response}", flush=True)
            except Exception as update_error:
                print(f"Error updating terex3: {str(update_error)}", flush=True)
                import traceback
                traceback.print_exc()
                # Non-fatal — log and continue

        if entrada_success:
            return {
                "success": True,
                "message": "Entrada registrada exitosamente",
                "qty": qty,
                "barcode": barcode,
                "product_name": product_info.get("name", "Producto no identificado") if product_info else "Producto no identificado",
                "terex3_updated": product_info is not None
            }
        else:
            raise HTTPException(status_code=500, detail="Failed to insert entrada")

    except Exception as e:
        print(f"Error in entrada mercancia 2: {str(e)}", flush=True)
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Error processing entrada: {str(e)}")


@app.get("/entradamercancia3/recientes")
async def get_recent_entries_3():
    """Get recent merchandise entries for store 2"""
    try:
        print("Fetching recent entrada_mercancia_3 records", flush=True)
        entries = await supabase_request(
            method="GET",
            endpoint="/rest/v1/entrada_mercancia_3",
            params={
                "select": "*",
                "order": "created_at.desc",
                "limit": "20"
            }
        )
        print(f"Retrieved {len(entries)} recent entries", flush=True)
        return {"success": True, "entries": entries}
    except Exception as e:
        print(f"Error fetching recent entries 2: {str(e)}", flush=True)
        return {"success": False, "error": str(e), "entries": []}

@app.get("/api/conteo3", response_model=List[ConteoEfectivoResponse])
async def get_conteo3(limit: Optional[int] = 100):
    """Get cash movement entries for store 2 (most recent first)"""
    try:
        data = await supabase_request(
            method="GET",
            endpoint="/rest/v1/conteo_efectivo3",
            params={"order": "created_at.desc", "limit": str(limit)}
        )

        if not data:
            return []

        return [
            ConteoEfectivoResponse(
                id=entry["id"],
                nombre=entry["nombre"],
                tipo=entry["tipo"],
                amount=entry["amount"],
                balance=entry["balance"],
                created_at=entry["created_at"],
                order_id=entry.get("order_id"),
                descripcion=entry.get("descripcion"),
                diferencia=entry.get("diferencia")
            )
            for entry in data
        ]
    except Exception as e:
        print(f"Error fetching conteo3: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/conteo3/transferencias")
async def get_conteo3_transferencias():
    """Órdenes pagadas por transferencia (Terex3, ayer y hoy), agrupadas por order_id.
    Display-only for /conteoefectivo — never touches conteo_efectivo3 or the cash balance."""
    try:
        mexico_tz = pytz.timezone("America/Mexico_City")
        today = datetime.now(mexico_tz).date()
        yesterday = today - timedelta(days=1)

        rows = await supabase_request(
            method="GET",
            endpoint=f"/rest/v1/{VENTAS_TABLE}",
            params={
                "select": "order_id,qty,price,fecha,hora,cliente",
                "payment_method": "eq.transferencia",
                "fecha": f"gte.{yesterday.isoformat()}",
                "order": "order_id.desc",
                "limit": "2000",
            },
        )

        orders = {}
        for r in rows or []:
            oid = r.get("order_id")
            if oid is None:
                continue
            o = orders.setdefault(oid, {
                "order_id": oid,
                "amount": 0.0,
                "fecha": r.get("fecha"),
                "hora": r.get("hora"),
                "cliente": r.get("cliente"),
            })
            o["amount"] += float(r.get("qty") or 0) * float(r.get("price") or 0)

        return sorted(orders.values(), key=lambda o: (o["fecha"] or "", o["hora"] or ""))
    except Exception as e:
        print(f"Error fetching transferencias2: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/conteo3", response_model=ConteoEfectivoResponse)
async def create_conteo3(data: ConteoEfectivoCreate):
    """Save a new cash movement entry for store 2"""
    try:
        current_balance = await get_current_balance3()

        new_balance = current_balance
        diferencia = None

        if data.tipo == 'credito':
            new_balance = current_balance + data.amount
        elif data.tipo == 'debito':
            new_balance = current_balance - data.amount
        elif data.tipo == 'conteo':
            diferencia = data.amount - current_balance
            new_balance = data.amount
        else:
            raise HTTPException(status_code=400, detail="Tipo must be 'credito', 'debito', or 'conteo'")

        result = await supabase_request(
            method="POST",
            endpoint="/rest/v1/conteo_efectivo3",
            json_data={
                "nombre": data.nombre,
                "tipo": data.tipo,
                "amount": data.amount,
                "balance": new_balance,
                "diferencia": diferencia
            }
        )
        entry = result[0] if isinstance(result, list) else result

        if data.tipo == 'conteo':
            if diferencia == 0:
                print(f"✅ Conteo correcto: ${current_balance:.2f} = ${data.amount:.2f}")
            elif diferencia > 0:
                print(f"💰 Sobrante: ${diferencia:.2f} (Esperado: ${current_balance:.2f}, Contado: ${data.amount:.2f})")
            else:
                print(f"⚠️ Faltante: ${abs(diferencia):.2f} (Esperado: ${current_balance:.2f}, Contado: ${data.amount:.2f})")

        return ConteoEfectivoResponse(
            id=entry["id"],
            nombre=entry["nombre"],
            tipo=entry["tipo"],
            amount=entry["amount"],
            balance=entry["balance"],
            created_at=entry["created_at"],
            order_id=entry.get("order_id"),
            descripcion=entry.get("descripcion"),
            diferencia=entry.get("diferencia")
        )
    except Exception as e:
        print(f"Error saving conteo3: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/conteo3/{conteo_id}")
async def delete_conteo3(conteo_id: int):
    """Delete a cash movement entry from store 2 and recalculate balances"""
    try:
        entry_data = await supabase_request(
            method="GET", endpoint="/rest/v1/conteo_efectivo3",
            params={"id": f"eq.{conteo_id}"}
        )

        if entry_data and entry_data[0].get('tipo') == 'inicial':
            raise HTTPException(status_code=400, detail="Cannot delete initial balance")

        await supabase_request(
            method="DELETE", endpoint="/rest/v1/conteo_efectivo3",
            params={"id": f"eq.{conteo_id}"}
        )

        await recalculate_balances3()

        return {"success": True, "message": "Entry deleted and balances recalculated"}
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error deleting conteo3: {e}")
        raise HTTPException(status_code=500, detail=str(e))


async def get_current_balance3() -> float:
    """Get the current balance from the last entry in conteo_efectivo3"""
    try:
        data = await supabase_request(
            method="GET", endpoint="/rest/v1/conteo_efectivo3",
            params={"order": "created_at.desc", "limit": "1"}
        )

        if not data:
            return 0.0

        return float(data[0].get('balance', 0.0))
    except Exception as e:
        print(f"Error getting current balance2: {e}")
        return 0.0


async def recalculate_balances3():
    """Recalculate all balances in conteo_efectivo3 after a deletion"""
    try:
        entries = await supabase_request(
            method="GET", endpoint="/rest/v1/conteo_efectivo3",
            params={"order": "created_at.asc"}
        )

        running_balance = 0.0

        for entry in entries:
            if entry['tipo'] == 'inicial':
                running_balance = float(entry['amount'])
            elif entry['tipo'] == 'credito':
                running_balance += float(entry['amount'])
            elif entry['tipo'] == 'debito':
                running_balance -= float(entry['amount'])

            await supabase_request(
                method="PATCH", endpoint="/rest/v1/conteo_efectivo3",
                params={"id": f"eq.{entry['id']}"},
                json_data={"balance": running_balance}
            )

        return running_balance
    except Exception as e:
        print(f"Error recalculating balances2: {e}")
        raise

@app.get("/conteoefectivo", response_class=HTMLResponse)
async def get_conteo_efectivo(request: Request):
    return templates.TemplateResponse(request=request, name="conteo_efectivo3.html", context={})

@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    return templates.TemplateResponse(request=request, name="index3.html", context={})

# Register router
app.include_router(router)

# Optional: serve static files
try:
    app.mount("/static", StaticFiles(directory="static"), name="static")
except Exception:
    pass

@app.get("/transferencias", response_class=HTMLResponse)
async def get_transferencias_page(request: Request):
    return templates.TemplateResponse(request=request, name="transferir_mercancia.html", context={"source_branch": "terex3"})


@app.post("/api/transferir")
async def api_transferir(request: Request):
    """Transfer merchandise between branches. Decrements source, increments target."""
    body = await request.json()
    source = body.get("source", "terex3")
    target = body.get("target", "terex1")
    items = body.get("items", [])

    VALID_BRANCHES = ("terex1", "terex2", "terex3")
    if source not in VALID_BRANCHES or target not in VALID_BRANCHES or source == target:
        raise HTTPException(status_code=400, detail="Invalid source/target")
    if not items:
        raise HTTPException(status_code=400, detail="No items")

    _tz = pytz.timezone("America/Mexico_City")
    now = datetime.now(_tz)
    transferred = 0
    total_qty = 0
    details = []

    for item in items:
        barcode = str(item.get("barcode", "")).strip()
        qty = int(item.get("qty", 1))
        if not barcode or qty < 1:
            continue

        inv = await supabase_request(
            method="GET",
            endpoint="/rest/v1/inventario1",
            params={"barcode": f"eq.{barcode}", "select": f"barcode,name,estilo,{source},{target}"}
        )
        if not inv:
            continue

        row = inv[0]
        source_stock = int(row.get(source) or 0)
        target_stock = int(row.get(target) or 0)
        actual_qty = min(qty, source_stock)
        if actual_qty <= 0:
            continue

        await supabase_request(
            method="PATCH",
            endpoint="/rest/v1/inventario1",
            params={"barcode": f"eq.{barcode}"},
            json_data={
                source: source_stock - actual_qty,
                target: target_stock + actual_qty,
            }
        )

        transferred += 1
        total_qty += actual_qty
        details.append(f"{row.get('name', barcode)} x{actual_qty}")

    # Log the transfer
    try:
        await supabase_request(
            method="POST",
            endpoint="/rest/v1/transferencias",
            json_data={
                "source": source,
                "target": target,
                "item_count": transferred,
                "total_qty": total_qty,
                "details": ", ".join(details[:20]),
                "created_by": "app3",
            }
        )
    except:
        pass

    # Telegram notification
    BRANCH_LABELS = {"terex1": "S1", "terex2": "S2", "terex3": "S3"}
    src_label = BRANCH_LABELS.get(source, source)
    tgt_label = BRANCH_LABELS.get(target, target)
    msg = f"📦 Transferencia {src_label} → {tgt_label}\n"
    msg += f"{transferred} productos, {total_qty} piezas\n"
    for d in details[:10]:
        msg += f"  - {d}\n"
    try:
        send_telegram_message(msg)
    except:
        pass

    return {"ok": True, "transferred": transferred, "total_qty": total_qty}


@app.get("/api/transferencias")
async def api_get_transferencias(limit: int = 10):
    try:
        data = await supabase_request(
            method="GET",
            endpoint="/rest/v1/transferencias",
            params={"select": "*", "order": "created_at.desc", "limit": str(limit)}
        )
        return data or []
    except:
        return []



@app.get("/api/pendientes3")
async def get_pendientes3():
    """Products in inventario1 where terex3 < 0"""
    try:
        data = await supabase_request(
            method="GET", endpoint="/rest/v1/inventario1",
            params={"terex3": "lt.0", "select": "barcode,name,estilo,marca,color,terex3", "order": "terex3.asc", "limit": "200"}
        )
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
@app.get("/inventoryxbarcode3", response_class=HTMLResponse)
async def get_inventoryxbarcode3_page(request: Request):
    return templates.TemplateResponse(request=request, name="inventoryxbarcode3.html", context={})


@app.get("/inventoryxbarcode3", response_class=HTMLResponse)
async def get_inventoryxbarcode3_page(request: Request):
    return templates.TemplateResponse(request=request, name="inventoryxbarcode3.html", context={})


@app.get("/api/inventoryxbarcode3")
async def get_product_by_barcode3(barcode: str):
    try:
        product_data, history_data = await asyncio.gather(
            supabase_request(
                method="GET", endpoint="/rest/v1/inventario1",
                params={"barcode": f"eq.{barcode}", "select": "barcode,name,estilo,estilo_id,marca,color,terex3", "limit": "1"}
            ),
            supabase_request(
                method="GET", endpoint="/rest/v1/terex3_history",
                params={"barcode": f"eq.{barcode}", "order": "created_at.desc", "limit": "20"}
            )
        )
        if not product_data:
            return None
        product = product_data[0]
        product["history"] = history_data

        return product
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.patch("/api/inventoryxbarcode3")
async def update_terex3(payload: dict):
    try:
        barcode      = payload.get("barcode")
        terex3       = payload.get("terex3")
        qty_before   = payload.get("qty_before", 0)
        product_name = payload.get("product_name", "")

        if barcode is None or terex3 is None:
            raise HTTPException(status_code=400, detail="barcode and terex3 required")

        matches    = int(qty_before) == int(terex3)
        difference = int(terex3) - int(qty_before)

        await asyncio.gather(
            supabase_request(
                method="PATCH", endpoint="/rest/v1/inventario1",
                params={"barcode": f"eq.{barcode}"},
                json_data={"terex3": terex3}
            ),
            supabase_request(
                method="POST", endpoint="/rest/v1/terex3_history",
                json_data={
                    "barcode":      int(barcode),
                    "product_name": product_name,
                    "qty_before":   int(qty_before),
                    "qty_counted":  int(terex3),
                    "matches":      matches,
                    "difference":   difference,
                }
            ),
            _log_inv_change(barcode, product_name, "terex3", "count", int(qty_before), int(terex3))
        )
        return {"success": True}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ── CHECK BARCODE MOBILE 2 ──────────────────────────────────────────
@app.get("/check_barcode_mobile3", response_class=HTMLResponse)
async def check_barcode_mobile3(request: Request):
    return templates.TemplateResponse(request=request, name="check_barcode_mobile3.html", context={})


@app.post("/api/check_barcode_mobile3/photo")
async def upload_barcode_photo2(
    barcode: str = Form(...),
    product_name: str = Form(""),
    estilo: str = Form(""),
    estilo_id: str = Form(""),
    color: str = Form(""),
    photo: UploadFile = File(...),
):
    try:
        contents = await photo.read()
        ext = photo.filename.rsplit(".", 1)[-1] if "." in photo.filename else "jpg"
        now_str = datetime.now().strftime("%Y%m%d_%H%M%S")
        uid = str(uuid.uuid4())[:8]
        storage_path = f"training/{barcode}/{now_str}_{uid}.{ext}"

        upload_url = f"{SUPABASE_URL}/storage/v1/object/barcode-photos/{storage_path}"
        upload_headers = {**HEADERS, "Content-Type": photo.content_type or "image/jpeg"}
        import httpx
        async with httpx.AsyncClient() as client:
            await client.post(upload_url, headers=upload_headers, content=contents)

        public_url = f"{SUPABASE_URL}/storage/v1/object/public/barcode-photos/{storage_path}"

        await supabase_request(
            method="POST", endpoint="/rest/v1/barcode_photos",
            json_data={
                "barcode": barcode,
                "product_name": product_name,
                "estilo": estilo,
                "estilo_id": int(estilo_id) if estilo_id.isdigit() else None,
                "color": color,
                "file_path": storage_path,
                "public_url": public_url,
            }
        )

        return {"success": True, "public_url": public_url}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ── CONTEO PREVIO DE MERCANCÍA ─────────────────────────────────────
@app.get("/api/counting-progress")
async def counting_progress_t2():
    """Return weekly counting progress for Sucursal 3 (terex3)."""
    try:
        from datetime import timedelta
        import pytz as _pytz
        tz = _pytz.timezone("America/Mexico_City")
        now = datetime.now(tz)
        monday = now - timedelta(days=now.weekday())
        monday_iso = monday.strftime("%Y-%m-%dT00:00:00")
        yesterday_str = (now - timedelta(days=1)).strftime("%Y-%m-%d")
        days_es = ["Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo"]

        # Count total with positive terex3 using RPC-style count
        import httpx
        async with httpx.AsyncClient() as client:
            r_total = await client.get(
                f"{SUPABASE_URL}/rest/v1/inventario1?terex3=gt.0&select=barcode",
                headers={**HEADERS, "Prefer": "count=exact", "Range": "0-0"},
            )
        cr = r_total.headers.get("Content-Range", "0-0/0")
        total = int(cr.split("/")[-1]) if "/" in cr else 0

        history = await supabase_request(
            method="GET", endpoint="/rest/v1/terex3_history",
            params={"created_at": f"gte.{monday_iso}", "select": "barcode,created_at", "limit": "5000"}
        )

        seen_week = set()
        seen_yesterday = set()
        daily = {d: set() for d in days_es}
        for row in history:
            bc = str(row["barcode"])
            dt = row["created_at"][:10]
            seen_week.add(bc)
            if dt == yesterday_str:
                seen_yesterday.add(bc)
            try:
                utc_dt = datetime.fromisoformat(row["created_at"].replace("Z","")).replace(tzinfo=_pytz.utc)
                idx = utc_dt.astimezone(tz).weekday()
                daily[days_es[idx]].add(bc)
            except: pass

        counted = len(seen_week)
        pct = round(counted / total * 100, 1) if total else 0
        remaining = max(total - counted, 0)
        weekday = now.weekday()
        days_left = max(6 - weekday, 1)
        import math as _math
        daily_target = _math.ceil(remaining / days_left) if remaining else 0
        if weekday >= 5:
            urgency = "🚨 CRÍTICO"
        elif weekday == 4 and pct < 90:
            urgency = "🔴 URGENTE"
        elif weekday == 3 and pct < 70:
            urgency = "🟠 ATENCIÓN"
        elif weekday == 2 and pct < 50:
            urgency = "🟡 AVISO"
        else:
            urgency = "🟢 EN TIEMPO"
        return {
            "branch": "terex3",
            "week_start": monday.strftime("%d/%m/%Y"),
            "today": now.strftime("%d/%m/%Y"),
            "today_name": days_es[now.weekday()],
            "total": total,
            "counted": counted,
            "yesterday": len(seen_yesterday),
            "remaining": remaining,
            "pct": pct,
            "daily": {d: len(s) for d, s in daily.items()},
            "daily_target": daily_target,
            "days_left": days_left,
            "urgency": urgency,
        }
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/conteo-previo", response_class=HTMLResponse)
async def conteo_previo_page2(request: Request):
    return templates.TemplateResponse(request=request, name="conteo_previo.html", context={})


@app.get("/inventario-rapido-modelo", response_class=HTMLResponse)
async def inventario_rapido_modelo(request: Request):
    return templates.TemplateResponse(request=request, name="inventario_rapido_modelo.html", context={})


@app.get("/api/inventario-rapido-modelo")
async def api_inventario_rapido_modelo(modelo: str):
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario1",
        params={"modelo": f"eq.{modelo}", "select": "estilo_id,terex3,inventario_estilos(nombre)", "limit": "2000"}
    )
    if not isinstance(rows, list):
        return []
    by_estilo = {}
    for row in rows:
        eid = row["estilo_id"]
        qty = max(0, int(row.get("terex3") or 0))
        if qty == 0:
            continue
        if eid not in by_estilo:
            e = row.get("inventario_estilos") or {}
            by_estilo[eid] = {"estilo_id": eid, "nombre": e.get("nombre", ""), "qty": 0}
        by_estilo[eid]["qty"] += qty
    return sorted(by_estilo.values(), key=lambda x: -x["qty"])


@app.get("/api/inventario-rapido-modelo/modelos")
async def api_modelos_list2():
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario_modelos",
        params={"select": "id,modelo", "order": "modelo.asc", "limit": "2000"}
    )
    if not isinstance(rows, list):
        return []
    return [r["modelo"] for r in rows if r.get("modelo")]


@app.get("/conteo-rapido-estilo", response_class=HTMLResponse)
async def conteo_rapido_estilo_page(request: Request):
    return templates.TemplateResponse(request=request, name="conteo_rapido_estilo.html", context={})


@app.get("/api/conteo-rapido-estilo/nombres")
async def api_estilo_nombres():
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario_estilos",
        params={"select": "id,nombre", "limit": "2000"}
    )
    if not isinstance(rows, list):
        return []
    return [{"id": r["id"], "nombre": r["nombre"]} for r in rows if r.get("nombre")]


@app.get("/api/conteo-rapido-estilo/thumbs")
async def api_estilo_thumbs():
    try:
        storage_headers = {"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}", "Content-Type": "application/json"}
        async with httpx.AsyncClient(timeout=15) as client:
            estilo_resp = await client.get(
                f"{SUPABASE_URL}/rest/v1/inventario_estilos",
                headers={**storage_headers, "Range": "0-1999"},
                params={"select": "id"},
            )
            ids = [r["id"] for r in (estilo_resp.json() if estilo_resp.status_code < 400 else [])]
            thumbs = {}
            for i in range(0, len(ids), 30):
                batch = ids[i:i+30]
                resps = await asyncio.gather(*[
                    client.post(f"{SUPABASE_URL}/storage/v1/object/list/images_estilos",
                                headers=storage_headers, json={"prefix": f"{eid}/", "limit": 1})
                    for eid in batch
                ], return_exceptions=True)
                for eid, resp in zip(batch, resps):
                    if isinstance(resp, Exception) or resp.status_code >= 400:
                        continue
                    files = resp.json() if isinstance(resp.json(), list) else []
                    for f in files:
                        if f.get("name") and f.get("id"):
                            thumbs[eid] = f"{SUPABASE_URL}/storage/v1/object/public/images_estilos/{eid}/{f['name']}"
                            break
        return thumbs
    except Exception as e:
        print(f"[thumbs] ERROR: {e}")
        return {}


@app.get("/api/conteo-rapido-estilo/estilos")
async def api_estilos_por_modelo(modelo: str):
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario1",
        params={"modelo": f"eq.{modelo}", "terex3": "gt.0", "select": "estilo_id,terex3", "limit": "5000"}
    )
    if not isinstance(rows, list):
        return []
    by_estilo = {}
    for row in rows:
        eid = row["estilo_id"]
        qty = int(row.get("terex3") or 0)
        by_estilo[eid] = by_estilo.get(eid, 0) + qty
    return sorted([{"estilo_id": k, "qty": v} for k, v in by_estilo.items()], key=lambda x: -x["qty"])


@app.post("/api/conteo-por-estilo")
async def save_conteo_por_estilo(payload: dict):
    rows = payload.get("rows", [])
    modelo = payload.get("modelo", "")
    if not rows:
        return {"ok": True, "inserted": 0}
    for row in rows:
        estilo_id = row.get("estilo_id")
        qty_contada = row.get("qty_contada", 0)
        stock_rows = await supabase_request(
            method="GET",
            endpoint="/rest/v1/inventario1",
            params={"select": "terex3", "modelo": f"eq.{modelo}", "estilo_id": f"eq.{estilo_id}", "limit": "5000"}
        )
        qty_sistema = sum(r.get("terex3") or 0 for r in (stock_rows or []))
        await supabase_request(
            method="POST",
            endpoint="/rest/v1/conteo_por_estilo",
            json_data={
                "sucursal":    payload.get("sucursal", "terex3"),
                "session_id":  payload.get("session_id"),
                "modelo":      modelo,
                "estilo_id":   estilo_id,
                "qty_contada": qty_contada,
                "qty_sistema": qty_sistema,
                "error":       qty_contada - qty_sistema,
            }
        )
    return {"ok": True, "inserted": len(rows)}


@app.post("/api/conteo-por-modelo")
async def save_conteo_por_modelo(payload: dict):
    modelo = payload.get("modelo", "")
    qty_contada = payload.get("qty_contada", 0)
    stock_rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario1",
        params={"select": "terex3", "modelo": f"eq.{modelo}", "limit": "10000"}
    )
    qty_sistema = sum(r.get("terex3") or 0 for r in (stock_rows or []))
    await supabase_request(
        method="POST",
        endpoint="/rest/v1/conteo_por_modelo",
        json_data={
            "sucursal":    payload.get("sucursal", "terex3"),
            "session_id":  payload.get("session_id"),
            "modelo":      modelo,
            "qty_contada": qty_contada,
            "qty_sistema": qty_sistema,
            "error":       qty_contada - qty_sistema,
        }
    )
    return {"ok": True}


@app.get("/api/inventario-rapido-modelo/ranking")
async def api_inventario_ranking_t3():
    rows = await supabase_request(
        method="GET",
        endpoint="/rest/v1/inventario1",
        params={"select": "modelo,terex3", "terex3": "gt.0", "limit": "10000"}
    )
    if not isinstance(rows, list):
        return []
    totals = {}
    for row in rows:
        m = (row.get("modelo") or "").strip().upper()
        if not m:
            continue
        totals[m] = totals.get(m, 0) + max(0, int(row.get("terex3") or 0))
    ranked = sorted(totals.items(), key=lambda x: -x[1])
    return [{"modelo": m, "qty": q, "rank": i + 1} for i, (m, q) in enumerate(ranked)]


@app.post("/api/conteo-previo")
async def save_conteo_previo3(payload: dict):
    try:
        caja_numero = int(payload.get("caja_numero", 0))
        fecha       = payload.get("fecha", datetime.now().strftime("%Y-%m-%d"))
        notas       = payload.get("notas", "")
        items       = payload.get("items", [])

        if not caja_numero or not items:
            return JSONResponse({"error": "caja_numero e items requeridos"}, status_code=400)

        rows = [
            {
                "caja_numero": caja_numero,
                "fecha":       fecha,
                "estilo":      it.get("estilo", "").strip().upper(),
                "modelo":      it["modelo"].strip().upper(),
                "color":       it["color"].strip().upper(),
                "qty":         int(it["qty"]),
                "notas":       notas,
            }
            for it in items
            if it.get("modelo") and it.get("color") and int(it.get("qty", 0)) > 0
        ]

        if not rows:
            return JSONResponse({"error": "No hay filas validas"}, status_code=400)

        await supabase_request(
            method="POST", endpoint="/rest/v1/conteo_previo",
            json_data=rows
        )

        total = sum(r["qty"] for r in rows)

        from collections import defaultdict, OrderedDict
        by_estilo = OrderedDict()
        for r in rows:
            est = r["estilo"] or "(sin estilo)"
            if est not in by_estilo:
                by_estilo[est] = defaultdict(list)
            by_estilo[est][r["modelo"]].append(r)

        lines = [f"📦 *CAJA {caja_numero}* — {fecha}\n"]
        for estilo, by_modelo in by_estilo.items():
            lines.append(f"▸ *{estilo}*")
            for modelo, its in by_modelo.items():
                lines.append(f"  {modelo}")
                for it in its:
                    lines.append(f"    {it['color']}: {it['qty']}")
            lines.append("")
        lines.append(f"TOTAL DE PZS .... {total} ✅")

        receipt = "\n".join(lines)
        return {"success": True, "total": total, "receipt": receipt, "saved": len(rows)}

    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/api/modelos-list")
async def modelos_list():
    """Return all modelo names from inventario_modelos."""
    try:
        rows = await supabase_request(
            method="GET",
            endpoint="/rest/v1/inventario_modelos?select=modelo&order=modelo.asc&limit=500",
        ) or []
        return sorted(set(r["modelo"] for r in rows if r.get("modelo")))
    except Exception:
        return []


@app.get("/api/conteo-previo/cajas")
async def list_conteo_cajas2():
    try:
        rows = await supabase_request(
            method="GET", endpoint="/rest/v1/conteo_previo",
            params={"select": "caja_numero,fecha,estilo,modelo,color,qty,reconciled,created_at", "order": "created_at.desc", "limit": "1000"}
        )

        from collections import defaultdict
        cajas = defaultdict(lambda: {"items": [], "total": 0, "fecha": "", "reconciled": True})
        for r in rows:
            c = cajas[r["caja_numero"]]
            c["items"].append(r)
            c["total"] += r["qty"]
            c["fecha"] = r["fecha"]
            if not r["reconciled"]:
                c["reconciled"] = False

        return [
            {"caja_numero": k, "fecha": v["fecha"], "total": v["total"],
             "reconciled": v["reconciled"], "items": v["items"]}
            for k, v in sorted(cajas.items(), key=lambda x: x[0], reverse=True)
        ]
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/api/conteo-previo/reconcile")
async def reconcile_caja3(caja_numero: int, fecha_from: str, fecha_to: str):
    try:
        counted, entradas1, entradas3 = await asyncio.gather(
            supabase_request(
                method="GET", endpoint="/rest/v1/conteo_previo",
                params={"caja_numero": f"eq.{caja_numero}", "select": "modelo,color,qty", "order": "modelo.asc"}
            ),
            supabase_request(
                method="GET", endpoint="/rest/v1/entrada_mercancia",
                params={"created_at": f"gte.{fecha_from}T00:00:00", "select": "estilo,qty", "limit": "2000"}
            ),
            supabase_request(
                method="GET", endpoint="/rest/v1/entrada_mercancia_3",
                params={"created_at": f"gte.{fecha_from}T00:00:00", "select": "estilo,qty", "limit": "2000"}
            ),
            return_exceptions=True
        )
        if isinstance(counted, Exception): counted = []
        if isinstance(entradas1, Exception): entradas1 = []
        if isinstance(entradas3, Exception): entradas3 = []
        total_counted  = sum(r["qty"] for r in counted)
        total_entered1 = sum(r["qty"] for r in entradas1)
        total_entered3 = sum(r["qty"] for r in entradas3)
        total_entered  = total_entered1 + total_entered3
        return {
            "caja_numero": caja_numero, "fecha_from": fecha_from, "fecha_to": fecha_to,
            "total_counted": total_counted, "total_entered": total_entered,
            "total_entered1": total_entered1, "total_entered3": total_entered3,
            "diff": total_entered - total_counted,
            "counted": counted, "entradas1": entradas1, "entradas3": entradas3,
        }
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.patch("/api/conteo-previo/{caja_numero}/mark-reconciled")
async def mark_reconciled3(caja_numero: int):
    try:
        await supabase_request(
            method="PATCH", endpoint="/rest/v1/conteo_previo",
            params={"caja_numero": f"eq.{caja_numero}"},
            json_data={"reconciled": True, "reconciled_at": datetime.now().isoformat()}
        )
        return {"success": True}
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.post("/api/conteo-previo/foto")
async def upload_conteo_foto2(
    caja_numero: int = Form(...),
    estilo: str = Form(""),
    fecha: str = Form(""),
    photo: UploadFile = File(...),
):
    try:
        contents = await photo.read()
        ext = photo.filename.rsplit(".", 1)[-1] if "." in photo.filename else "jpg"
        now_str = datetime.now().strftime("%Y%m%d_%H%M%S")
        uid = str(uuid.uuid4())[:8]
        safe_estilo = estilo.replace(" ", "_").replace("/", "-")[:40]
        storage_path = f"conteo_previo/caja{caja_numero}/{safe_estilo}_{now_str}_{uid}.{ext}"
        upload_url = f"{SUPABASE_URL}/storage/v1/object/barcode-photos/{storage_path}"
        import httpx
        async with httpx.AsyncClient() as client:
            await client.post(upload_url, headers={**HEADERS, "Content-Type": photo.content_type or "image/jpeg"}, content=contents)
        return {"success": True}
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/api/inventory/variance-by-estilo")
async def variance_by_estilo_t2(limit: int = 10):
    """Top estilos by absolute variance from last week's terex3 history."""
    try:
        import pytz as _pytz
        from datetime import timedelta
        tz = _pytz.timezone("America/Mexico_City")
        now = datetime.now(tz)
        last_monday = now - timedelta(days=now.weekday() + 7)
        last_sunday  = last_monday + timedelta(days=6)
        from_iso = last_monday.strftime("%Y-%m-%dT00:00:00")
        to_iso   = last_sunday.strftime("%Y-%m-%dT23:59:59")

        rows, inv = await asyncio.gather(
            supabase_request(
                method="GET", endpoint="/rest/v1/terex3_history",
                params={"created_at": f"gte.{from_iso}", "select": "barcode,product_name,difference,qty_before,qty_counted", "limit": "5000"}
            ),
            supabase_request(
                method="GET", endpoint="/rest/v1/inventario1",
                params={"select": "barcode,estilo", "limit": "5000"}
            ),
            return_exceptions=True
        )
        if isinstance(rows, Exception): rows = []
        if isinstance(inv, Exception): inv = []

        bc_to_estilo = {str(r["barcode"]): (r.get("estilo") or "Sin estilo") for r in inv}

        from collections import defaultdict
        estilo_stats = defaultdict(lambda: {"abs_diff": 0, "net_diff": 0, "counts": 0, "pos": 0, "neg": 0})
        for r in rows:
            bc = str(r["barcode"])
            estilo = bc_to_estilo.get(bc) or r.get("product_name") or "Sin estilo"
            diff = r.get("difference") or 0
            estilo_stats[estilo]["abs_diff"] += abs(diff)
            estilo_stats[estilo]["net_diff"] += diff
            estilo_stats[estilo]["counts"]   += 1
            if diff > 0: estilo_stats[estilo]["pos"] += 1
            if diff < 0: estilo_stats[estilo]["neg"] += 1

        top = sorted(estilo_stats.items(), key=lambda x: x[1]["abs_diff"], reverse=True)[:limit]
        return [{"estilo": k, **v} for k, v in top]
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/api/inventory/error-rate")
async def inventory_error_rate_t2():
    """Last-week error rate for Terex3: discrepancy between system qty and physical count."""
    try:
        import pytz as _pytz
        from datetime import timedelta
        tz = _pytz.timezone("America/Mexico_City")
        now = datetime.now(tz)
        last_monday = now - timedelta(days=now.weekday() + 7)
        last_sunday  = last_monday + timedelta(days=6)
        from_iso = last_monday.strftime("%Y-%m-%dT00:00:00")
        to_iso   = last_sunday.strftime("%Y-%m-%dT23:59:59")

        rows = await supabase_request(
            method="GET", endpoint="/rest/v1/terex3_history",
            params={"created_at": f"gte.{from_iso}", "select": "qty_before,qty_counted,matches,difference", "limit": "5000"}
        )

        total_skus    = len(rows)
        error_skus    = sum(1 for r in rows if not r.get("matches"))
        total_counted = sum(r.get("qty_counted", 0) or 0 for r in rows)
        total_diff    = sum(abs(r.get("difference", 0) or 0) for r in rows)
        net_diff      = sum((r.get("difference", 0) or 0) for r in rows)
        error_rate    = round(total_diff / total_counted * 100, 1) if total_counted else 0

        return {
            "branch":        "terex3",
            "week_from":     last_monday.strftime("%d/%m/%Y"),
            "week_to":       last_sunday.strftime("%d/%m/%Y"),
            "total_skus":    total_skus,
            "error_skus":    error_skus,
            "total_counted": total_counted,
            "total_diff":    total_diff,
            "net_diff":      net_diff,
            "error_rate":    error_rate,
        }
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/ver-nota-especifica/{nota_id}", response_class=HTMLResponse)
async def ver_nota_especifica(request: Request, nota_id: int):
    items = await supabase_request(
        method="GET",
        endpoint=f"/rest/v1/{VENTAS_TABLE}",
        params={"select": "order_id,fecha,hora,estilo,modelo,color,qty,price,cliente,whatsapp",
                "order_id": f"eq.{nota_id}", "order": "id.asc", "limit": "500"}
    )
    rows = items or []
    total = sum((r.get("qty") or 1) * (r.get("price") or 0) for r in rows)
    fecha = rows[0].get("fecha", "") if rows else ""
    cliente = rows[0].get("cliente", "") if rows else ""
    html = f"""<!DOCTYPE html><html lang="es"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nota #{nota_id}</title>
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{background:#0F0F23;color:#fff;font-family:-apple-system,sans-serif;padding:20px}}
h2{{color:#7C4DFF;margin-bottom:4px}}
.sub{{color:#888;font-size:.8rem;margin-bottom:16px}}
table{{width:100%;border-collapse:collapse;background:#16213E;border-radius:10px;overflow:hidden}}
th{{background:#0d1526;padding:10px 14px;text-align:left;font-size:.7rem;color:#888;text-transform:uppercase}}
td{{padding:9px 14px;border-bottom:1px solid #12121f;font-size:.85rem}}
.total-row td{{font-weight:800;font-size:.95rem;color:#00C853;background:#0a1a0a}}
.btn{{display:inline-block;margin-top:16px;padding:10px 20px;background:#7C4DFF;color:#fff;text-decoration:none;border-radius:8px;font-weight:700}}
.empty{{text-align:center;padding:40px;color:#555}}
</style></head><body>
<h2>Nota #{nota_id}</h2>
<div class="sub">Terex 2 &nbsp;·&nbsp; {fecha} &nbsp;·&nbsp; {cliente or "—"}</div>
<a class="btn" href="/ver-nota-especifica/{nota_id}/pdf">Descargar PDF</a>
<br><br>
<table><thead><tr><th>#</th><th>Estilo</th><th>Modelo</th><th>Color</th><th style="text-align:right">Cant</th><th style="text-align:right">Precio</th><th style="text-align:right">Subtotal</th></tr></thead>
<tbody>"""
    if not rows:
        html += f'<tr><td colspan="7" class="empty">Nota {nota_id} no encontrada en Terex 2</td></tr>'
    for i, r in enumerate(rows, 1):
        qty = r.get("qty") or 1
        price = r.get("price") or 0
        sub = qty * price
        html += f"<tr><td>{i}</td><td>{r.get('estilo','')}</td><td>{r.get('modelo','')}</td><td>{r.get('color','')}</td><td style='text-align:right'>{qty}</td><td style='text-align:right'>${price:,.0f}</td><td style='text-align:right'>${sub:,.0f}</td></tr>"
    html += f"""</tbody>
<tfoot><tr class="total-row"><td colspan="6">TOTAL</td><td style="text-align:right">${total:,.0f}</td></tr></tfoot>
</table></body></html>"""
    return HTMLResponse(html)


@app.get("/ver-nota-especifica/{nota_id}/pdf")
async def ver_nota_pdf(nota_id: int):
    items = await supabase_request(
        method="GET",
        endpoint=f"/rest/v1/{VENTAS_TABLE}",
        params={"select": "order_id,fecha,estilo,modelo,color,qty,price,cliente",
                "order_id": f"eq.{nota_id}", "order": "id.asc", "limit": "500"}
    )
    rows = items or []
    if not rows:
        raise HTTPException(status_code=404, detail=f"Nota {nota_id} no encontrada")

    mexico_tz = pytz.timezone("America/Mexico_City")
    now = datetime.now(mexico_tz)
    fecha_nota = rows[0].get("fecha", now.strftime("%Y-%m-%d"))
    cliente_nota = rows[0].get("cliente", "") or ""

    width = 80 * mm
    margin = 3 * mm
    item_h = 9 * mm
    height = 28 * mm + (len(rows) * item_h) + 20 * mm + 2 * margin
    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=(width, height))
    y = height - margin

    c.setFont("Helvetica-Bold", 14)
    c.drawCentredString(width / 2, y, "TEREX3"); y -= 14
    c.setFont("Helvetica-Bold", 11)
    c.drawCentredString(width / 2, y, f"Nota #{nota_id}"); y -= 13
    c.setFont("Helvetica", 9)
    c.drawCentredString(width / 2, y, f"{fecha_nota}  {cliente_nota}"); y -= 10
    c.setLineWidth(0.5); c.line(margin, y, width - margin, y); y -= 12

    c.setFont("Helvetica-Bold", 9)
    c.drawString(margin, y, "Producto"); c.drawRightString(width - margin, y, "Subtotal"); y -= 10
    c.line(margin, y + 4, width - margin, y + 4)
    c.setFont("Helvetica", 9)
    total = 0
    for r in rows:
        qty = r.get("qty") or 1
        price = float(r.get("price") or 0)
        sub = qty * price
        total += sub
        name = f"{r.get('estilo','')} {r.get('modelo','')} {r.get('color','')}".strip()
        name = name[:38] + "…" if len(name) > 38 else name
        c.drawString(margin, y, f"{qty}x {name}"); c.drawRightString(width - margin, y, f"${sub:,.0f}"); y -= 9

    c.line(margin, y, width - margin, y); y -= 10
    c.setFont("Helvetica-Bold", 10)
    c.drawString(margin, y, "TOTAL:"); c.drawRightString(width - margin, y, f"${total:,.0f}"); y -= 12
    c.setFont("Helvetica", 8)
    c.drawCentredString(width / 2, y, "Gracias por su compra")

    c.showPage(); c.save(); buf.seek(0)
    return StreamingResponse(buf, media_type="application/pdf",
                             headers={"Content-Disposition": f"inline; filename=Nota_{nota_id}.pdf"})


# ENTRYPOINT
# ─────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)