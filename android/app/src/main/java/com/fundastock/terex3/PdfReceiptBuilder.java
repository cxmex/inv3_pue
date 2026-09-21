package com.fundastock.terex3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 80mm thermal-receipt PDF with a WhatsApp loyalty QR code.
 * Mirrors _build_receipt_pdf_with_qr(show_items=False) in app.py.
 */
public class PdfReceiptBuilder {

    private static final float MM = 2.834645f; // points per mm at 72dpi, matches reportlab's `mm` unit

    public static File build(Context context, List<CartItem> items, double total, int orderId, String redemptionToken)
            throws IOException, WriterException {

        int totalPieces = 0;
        for (CartItem it : items) totalPieces += it.qty;
        double rewardAmount = Math.round(total * 0.01 * 100) / 100.0;

        int width = Math.round(80 * MM);
        int height = Math.round(165 * MM);
        float margin = 3 * MM;

        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(width, height, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        float y = height - margin;
        float centerX = width / 2f;

        TimeZone mx = TimeZone.getTimeZone("America/Mexico_City");
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        dateFmt.setTimeZone(mx);
        timeFmt.setTimeZone(mx);
        Date now = new Date();

        // Header
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(14);
        canvas.drawText("TEREX3", centerX, y, paint);
        y -= 14;

        paint.setTextSize(11);
        canvas.drawText("Ticket #" + orderId, centerX, y, paint);
        y -= 13;

        paint.setFakeBoldText(false);
        paint.setTextSize(9);
        canvas.drawText(dateFmt.format(now) + "  " + timeFmt.format(now), centerX, y, paint);
        y -= 10;

        paint.setStrokeWidth(0.7f);
        canvas.drawLine(margin, y, width - margin, y, paint);
        y -= 12;

        // Totals
        paint.setTextSize(10);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Total piezas:", margin, y, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.valueOf(totalPieces), width - margin, y, paint);
        y -= 13;

        paint.setFakeBoldText(true);
        paint.setTextSize(13);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("TOTAL:", margin, y, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.US, "$%.2f", total), width - margin, y, paint);
        y -= 16;

        // Dashed separator
        Paint dashPaint = new Paint(paint);
        dashPaint.setPathEffect(new DashPathEffect(new float[]{1f, 2f}, 0));
        canvas.drawLine(margin, y, width - margin, y, dashPaint);
        y -= 12;

        paint.setFakeBoldText(true);
        paint.setTextSize(9);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ESCANEA ESTE QR CODE Y OBTEN", centerX, y, paint);
        y -= 10;
        canvas.drawText("1% PARA TU SIGUIENTE COMPRA", centerX, y, paint);
        y -= 10;

        paint.setFakeBoldText(false);
        paint.setTextSize(8);
        paint.setColor(Color.GRAY);
        canvas.drawText(String.format(Locale.US, "Credito a obtener: $%.2f", rewardAmount), centerX, y, paint);
        paint.setColor(Color.BLACK);
        y -= 12;

        // QR code → WhatsApp deep link with the redemption token
        String prefilled = URLEncoder.encode("CANJEAR:" + redemptionToken, StandardCharsets.UTF_8.name());
        String qrUrl = "https://wa.me/" + Config.WHATSAPP_BUSINESS_NUMBER + "?text=" + prefilled;

        int qrSize = Math.round(40 * MM);
        Bitmap qrBitmap = generateQrBitmap(qrUrl, qrSize);
        float xQr = (width - qrSize) / 2f;
        float yQr = y - qrSize;
        canvas.drawBitmap(qrBitmap, xQr, yQr, null);
        y = yQr - 8;

        paint.setTextSize(7);
        paint.setColor(Color.GRAY);
        canvas.drawText("TAMBIEN PODRA VER EL DETALLE DE SU", centerX, y, paint);
        y -= 8;
        canvas.drawText("COMPRA, UNA VEZ ESCANEADO EL", centerX, y, paint);
        y -= 8;
        canvas.drawText("CODIGO QR EN SU WHATSAPP", centerX, y, paint);
        y -= 10;

        canvas.drawText("¡Gracias por su compra!", centerX, y, paint);
        paint.setColor(Color.BLACK);

        document.finishPage(page);

        File dir = new File(context.getCacheDir(), "tickets");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "ticket_terex3_" + orderId + "_" + System.currentTimeMillis() + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            document.writeTo(fos);
        }
        document.close();
        return file;
    }

    private static Bitmap generateQrBitmap(String content, int sizePx) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
        for (int x = 0; x < sizePx; x++) {
            for (int yy = 0; yy < sizePx; yy++) {
                bmp.setPixel(x, yy, matrix.get(x, yy) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }
}
