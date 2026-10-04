package com.blushdesk.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.ui.theme.BrandPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * Draws an A4 receipt with Android's native [PdfDocument] (vector text and shapes, so it stays
 * sharp when printed) and writes it to [generate]'s destination file.
 *
 * Page 1: showroom header band, "billed to" card with the purchase facts, then the itemized table.
 * When the items do not fit, the table continues on further pages under a compact header, with
 * its column headings repeated. The total follows the last row. Every page has the footer, a page
 * number (when there is more than one) and a large semi-transparent PAID stamp.
 * Units are PDF points (1/72 in); A4 is 595 x 842.
 */
class PdfReceiptGenerator {

    /**
     * Only a paid order may be receipted, so the rule lives here as well as in the UI: a caller
     * that forgets to check cannot produce a "PAID" receipt for an unpaid order.
     */
    suspend fun generate(
        destination: File,
        operator: OperatorProfile,
        buyer: Buyer,
        order: OrderWithItems,
        zone: ZoneId = ZoneId.systemDefault(),
        issuedAt: Instant = Instant.now(),
    ): File = withContext(Dispatchers.IO) {
        require(order.order.paymentStatus.isPaid) { "A receipt can only be issued for a paid order" }
        require(order.order.buyerId == buyer.id) { "Order ${order.order.id} does not belong to buyer ${buyer.id}" }
        require(order.items.isNotEmpty()) { "Order ${order.order.id} has no items" }

        destination.absoluteFile.parentFile?.mkdirs()
        val partial = File(destination.absoluteFile.parentFile, destination.name + ".part")
        val avatar = AppFiles.localFile(operator.profileImageUri)?.let { loadSampled(it.path, AVATAR_DECODE_PX) }
        val document = PdfDocument()
        try {
            val renderer = ReceiptRenderer(operator, buyer, order, zone, issuedAt, avatar)
            val plan = renderer.paginate()
            plan.forEachIndexed { index, pagePlan ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                renderer.drawPage(page.canvas, pagePlan, pageNumber = index + 1, pageCount = plan.size)
                document.finishPage(page)
            }
            FileOutputStream(partial).use { document.writeTo(it) }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        } finally {
            document.close()
            avatar?.recycle()
        }
        destination
    }

    private fun loadSampled(path: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** One table row: the item, its wrapped name and the row height it needs. */
    private class Row(val item: OrderItem, val name: StaticLayout, val height: Float)

    /** What goes on one page. */
    private class PagePlan(val rows: List<Row>, val isFirst: Boolean, val showTotal: Boolean)

    private class ReceiptRenderer(
        private val operator: OperatorProfile,
        private val buyer: Buyer,
        private val order: OrderWithItems,
        private val zone: ZoneId,
        private val issuedAt: Instant,
        private val avatar: Bitmap?,
    ) {
        private lateinit var canvas: Canvas
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Paint(Paint.ANTI_ALIAS_FLAG)

        private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
        private val bold = Typeface.create("sans-serif", Typeface.BOLD)

        // Item names are laid out ahead of drawing, so they get their own paint that nothing mutates.
        private val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f
            typeface = bold
            color = argb(BrandPalette.DARK_TEXT)
        }

        private val storeName = operator.storeName.ifBlank { "Showroom" }
        private val number = Formats.orderNumber(order.order.id)

        // ---- Pagination ---------------------------------------------------------------------

        /** Splits the item rows over as many pages as needed, keeping the total after the last row. */
        fun paginate(): List<PagePlan> {
            val rows = order.items.map { item ->
                val layout = StaticLayout.Builder
                    .obtain(item.productName, 0, item.productName.length, itemPaint, ITEM_WIDTH.toInt())
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                Row(item, layout, maxOf(ROW_MIN_H, layout.height + 2 * ROW_PADDING))
            }

            val pages = mutableListOf<PagePlan>()
            var current = mutableListOf<Row>()
            var y = FIRST_TABLE_TOP + TABLE_HEADER_H
            for (row in rows) {
                if (current.isNotEmpty() && y + row.height > CONTENT_BOTTOM) {
                    pages += PagePlan(current, isFirst = pages.isEmpty(), showTotal = false)
                    current = mutableListOf()
                    y = NEXT_TABLE_TOP + TABLE_HEADER_H
                }
                current += row
                y += row.height
            }
            if (y + TOTAL_GAP + TOTAL_H > CONTENT_BOTTOM) {
                // The total does not fit under the last row: it moves to a page of its own.
                pages += PagePlan(current, isFirst = pages.isEmpty(), showTotal = false)
                current = mutableListOf()
            }
            pages += PagePlan(current, isFirst = pages.isEmpty(), showTotal = true)
            return pages
        }

        fun drawPage(target: Canvas, plan: PagePlan, pageNumber: Int, pageCount: Int) {
            canvas = target
            val tableTop = if (plan.isFirst) {
                header()
                billedTo()
                FIRST_TABLE_TOP
            } else {
                continuationHeader()
                NEXT_TABLE_TOP
            }
            var bottom = tableTop
            if (plan.rows.isNotEmpty()) bottom = table(tableTop, plan.rows)
            if (plan.showTotal) total(bottom)
            footer(pageNumber, pageCount)
            paidStamp()
        }

        // ---- Header band --------------------------------------------------------------------

        private fun header() {
            rect(0f, 0f, W, HEADER_H, BrandPalette.DEEP_MAGENTA)
            rect(0f, HEADER_H, W, HEADER_H + 6f, BrandPalette.VIBRANT_ROSE)

            avatar(MARGIN + 34f, 68f, 34f)

            val textX = MARGIN + 86f
            val rightEdge = W - MARGIN
            val titleWidth = 150f
            line(storeName, textX, 56f, 22f, BrandPalette.WHITE, bold = true, maxWidth = rightEdge - titleWidth - 14f - textX)

            var y = 78f
            listOf(operator.fullName, operator.email, operator.phoneNumber).filter { it.isNotBlank() }.forEach {
                line(it, textX, y, 10.5f, BrandPalette.SOFT_PINK, maxWidth = rightEdge - titleWidth - 14f - textX)
                y += 15f
            }

            line("RECEIPT", rightEdge, 56f, 30f, BrandPalette.WHITE, bold = true, align = Paint.Align.RIGHT, spacing = 0.08f)
            line("No. $number", rightEdge, 78f, 11.5f, BrandPalette.SOFT_PINK, bold = true, align = Paint.Align.RIGHT)
            line("Issued ${Formats.date(issuedAt, zone)}", rightEdge, 94f, 10.5f, BrandPalette.SOFT_PINK, align = Paint.Align.RIGHT)
        }

        /** Slim band on pages 2+, so a loose page still says whose receipt it is. */
        private fun continuationHeader() {
            rect(0f, 0f, W, CONT_HEADER_H, BrandPalette.DEEP_MAGENTA)
            rect(0f, CONT_HEADER_H, W, CONT_HEADER_H + 4f, BrandPalette.VIBRANT_ROSE)
            line(storeName, MARGIN, 40f, 16f, BrandPalette.WHITE, bold = true, maxWidth = 260f)
            line("Receipt No. $number (continued)", W - MARGIN, 40f, 11f, BrandPalette.SOFT_PINK, bold = true, align = Paint.Align.RIGHT)
        }

        /** The operator's photo in a circle, or their initials on a soft-pink disc. */
        private fun avatar(cx: Float, cy: Float, r: Float) {
            if (avatar != null) {
                val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                canvas.withClip(path) {
                    // Center-crop: scale to cover the circle, then center.
                    val scale = (2 * r) / minOf(avatar.width, avatar.height)
                    val w = avatar.width * scale
                    val h = avatar.height * scale
                    drawBitmap(avatar, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), shape)
                }
            } else {
                shape.style = Paint.Style.FILL
                shape.color = argb(BrandPalette.SOFT_PINK)
                canvas.drawCircle(cx, cy, r, shape)
                val name = operator.fullName.ifBlank { storeName }
                line(Formats.initials(name), cx, cy + 10f, 28f, BrandPalette.DEEP_MAGENTA, bold = true, align = Paint.Align.CENTER)
            }
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 2.5f
            shape.color = argb(BrandPalette.WHITE)
            canvas.drawCircle(cx, cy, r, shape)
        }

        // ---- Billed-to card -----------------------------------------------------------------

        private fun billedTo() {
            val top = 168f
            line("BILLED TO", MARGIN, top, 9f, BrandPalette.VIBRANT_ROSE, bold = true, spacing = 0.14f)
            val cardTop = top + 10f
            val cardBottom = cardTop + 124f
            roundRect(MARGIN, cardTop, W - MARGIN, cardBottom, 12f, BrandPalette.LAVENDER_BLUSH, BrandPalette.OUTLINE_SOFT)

            val leftX = MARGIN + 18f
            val dividerX = 318f
            line(buyer.fullName, leftX, cardTop + 34f, 15f, BrandPalette.DEEP_MAGENTA, bold = true, maxWidth = dividerX - leftX - 14f)
            var y = cardTop + 56f
            if (buyer.contactNumber.isNotBlank()) {
                line(buyer.contactNumber, leftX, y, 11f, BrandPalette.DARK_TEXT, maxWidth = dividerX - leftX - 14f)
                y += 18f
            }
            if (buyer.facebookName.isNotBlank()) {
                line("Facebook: ${buyer.facebookName}", leftX, y, 11f, BrandPalette.DARK_TEXT, maxWidth = dividerX - leftX - 14f)
                y += 18f
            }
            if (buyer.email.isNotBlank()) {
                line(buyer.email, leftX, y, 11f, BrandPalette.MUTED_TEXT_ON_TINT, maxWidth = dividerX - leftX - 14f)
            }

            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 1f
            shape.color = argb(BrandPalette.OUTLINE_SOFT)
            canvas.drawLine(dividerX, cardTop + 14f, dividerX, cardBottom - 14f, shape)

            val keyX = dividerX + 18f
            val valueX = W - MARGIN - 18f
            val paymentStatusLabel = "Payment status"
            val facts = listOf(
                "Purchase date" to Formats.date(order.order.purchaseDateTime, zone),
                "Purchase time" to Formats.time(order.order.purchaseDateTime, zone),
                "Payment method" to order.order.paymentMode.label,
                paymentStatusLabel to order.order.paymentStatus.label.uppercase(),
                "Fulfillment status" to order.order.fulfillmentStatus.label,
            )
            facts.forEachIndexed { index, (key, value) ->
                val rowY = cardTop + 28f + index * 20f
                line(key, keyX, rowY, 9.5f, BrandPalette.MUTED_TEXT_ON_TINT)
                val color = if (key == paymentStatusLabel) BrandPalette.tone(order.order.paymentStatus).foreground else BrandPalette.DARK_TEXT
                line(value, valueX, rowY, 10.5f, color, bold = true, align = Paint.Align.RIGHT, maxWidth = valueX - keyX - 90f)
            }
        }

        // ---- Itemized table -----------------------------------------------------------------

        /** Draws the column headings and [rows] from [top]; returns the y of the table's bottom edge. */
        private fun table(top: Float, rows: List<Row>): Float {
            // Header: rose bar with rounded top corners.
            shape.style = Paint.Style.FILL
            shape.color = argb(BrandPalette.VIBRANT_ROSE)
            canvas.drawRoundRect(RectF(MARGIN, top, W - MARGIN, top + TABLE_HEADER_H), 8f, 8f, shape)
            canvas.drawRect(MARGIN, top + TABLE_HEADER_H / 2, W - MARGIN, top + TABLE_HEADER_H, shape)

            val headerBaseline = top + 18f
            line("ITEM", ITEM_X, headerBaseline, 9.5f, BrandPalette.WHITE, bold = true, spacing = 0.1f)
            line("UNIT PRICE", UNIT_RIGHT, headerBaseline, 9.5f, BrandPalette.WHITE, bold = true, align = Paint.Align.RIGHT, spacing = 0.1f)
            line("QTY", QTY_CENTER, headerBaseline, 9.5f, BrandPalette.WHITE, bold = true, align = Paint.Align.CENTER, spacing = 0.1f)
            line("AMOUNT", AMOUNT_RIGHT, headerBaseline, 9.5f, BrandPalette.WHITE, bold = true, align = Paint.Align.RIGHT, spacing = 0.1f)

            var y = top + TABLE_HEADER_H
            rows.forEachIndexed { index, row ->
                // Zebra striping so a long list of products stays easy to read across.
                if (index % 2 == 1) rect(MARGIN + 0.5f, y, W - MARGIN - 0.5f, y + row.height, BrandPalette.LAVENDER_BLUSH)
                canvas.withTranslation(ITEM_X, y + ROW_PADDING) { row.name.draw(this) }

                // Numbers sit on the first line of the name; they shrink to their column, never truncate.
                val baseline = y + ROW_PADDING + row.name.getLineBaseline(0)
                line(Money.format(row.item.unitPrice), UNIT_RIGHT, baseline, 11f, BrandPalette.DARK_TEXT, align = Paint.Align.RIGHT, fitWidth = UNIT_WIDTH)
                line(row.item.quantity.toString(), QTY_CENTER, baseline, 11f, BrandPalette.DARK_TEXT, align = Paint.Align.CENTER, fitWidth = QTY_WIDTH)
                line(Money.format(row.item.lineTotal), AMOUNT_RIGHT, baseline, 11.5f, BrandPalette.DARK_TEXT, bold = true, align = Paint.Align.RIGHT, fitWidth = AMOUNT_WIDTH)

                y += row.height
                if (index < rows.lastIndex) {
                    shape.style = Paint.Style.STROKE
                    shape.strokeWidth = 0.75f
                    shape.color = argb(BrandPalette.OUTLINE_SOFT)
                    canvas.drawLine(MARGIN, y, W - MARGIN, y, shape)
                }
            }

            // Outline of the whole table, drawn last so it sits on top of the bar's corners.
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 1f
            shape.color = argb(BrandPalette.OUTLINE_SOFT)
            canvas.drawRoundRect(RectF(MARGIN, top, W - MARGIN, y), 8f, 8f, shape)
            return y
        }

        private fun total(after: Float) {
            val boxW = 290f
            val left = W - MARGIN - boxW
            val top = after + TOTAL_GAP
            roundRect(left, top, W - MARGIN, top + TOTAL_H, 12f, BrandPalette.SOFT_PINK, null)
            // Label row on top, the amount on its own line below so even a very large total has the full box width.
            line("TOTAL PAID", left + 18f, top + 28f, 10f, BrandPalette.DEEP_MAGENTA, bold = true, spacing = 0.14f)
            val items = order.items.size
            val summary = "${if (items == 1) "1 item" else "$items items"} · via ${order.order.paymentMode.label}"
            line(summary, W - MARGIN - 18f, top + 28f, 9.5f, BrandPalette.MUTED_TEXT_ON_TINT, align = Paint.Align.RIGHT)
            line(
                Money.format(order.order.totalAmount), W - MARGIN - 18f, top + 66f, 28f, BrandPalette.DEEP_MAGENTA,
                bold = true, align = Paint.Align.RIGHT, fitWidth = boxW - 36f,
            )
        }

        // ---- Footer and watermark -----------------------------------------------------------

        private fun footer(pageNumber: Int, pageCount: Int) {
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 1f
            shape.color = argb(BrandPalette.OUTLINE_SOFT)
            canvas.drawLine(MARGIN, H - 96f, W - MARGIN, H - 96f, shape)

            line("Thank you for shopping with $storeName!", W / 2, H - 70f, 13f, BrandPalette.DEEP_MAGENTA, bold = true, align = Paint.Align.CENTER, maxWidth = W - 2 * MARGIN)
            line("This is a computer-generated receipt and does not need a signature.", W / 2, H - 52f, 9f, BrandPalette.MUTED_TEXT, align = Paint.Align.CENTER)
            line("Generated ${Formats.dateTime(issuedAt, zone)} with $APP_NAME", W / 2, H - 38f, 8.5f, BrandPalette.MUTED_TEXT, align = Paint.Align.CENTER)
            if (pageCount > 1) {
                line("Page $pageNumber of $pageCount", W - MARGIN, H - 20f, 8.5f, BrandPalette.MUTED_TEXT, bold = true, align = Paint.Align.RIGHT)
            }
            rect(0f, H - 10f, W, H, BrandPalette.VIBRANT_ROSE)
        }

        /** A rotated, ~16%-opaque rubber-stamp "PAID" across the middle of the page. */
        private fun paidStamp() {
            val alpha = 42
            applyStyle(124f, BrandPalette.VIBRANT_ROSE, bold = true)
            text.letterSpacing = 0.06f
            text.alpha = alpha
            text.textAlign = Paint.Align.CENTER
            val metrics = text.fontMetrics
            val textWidth = text.measureText("PAID")
            val frame = RectF(-textWidth / 2 - 34f, metrics.ascent - 8f, textWidth / 2 + 34f, metrics.descent + 14f)

            // Drawn around the origin, then moved to the page center and tilted like a rubber stamp.
            canvas.withTranslation(W / 2, H / 2 + 20f) {
                rotate(-22f)
                shape.style = Paint.Style.STROKE
                shape.color = argb(BrandPalette.VIBRANT_ROSE, alpha)
                shape.strokeWidth = 9f
                drawRoundRect(frame, 22f, 22f, shape)
                shape.strokeWidth = 3f
                frame.inset(15f, 15f)
                drawRoundRect(frame, 12f, 12f, shape)
                drawText("PAID", 0f, 0f, text)
            }
        }

        // ---- Primitives ---------------------------------------------------------------------

        private fun applyStyle(size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) {
            text.textSize = size
            text.color = argb(color)
            text.typeface = if (bold) this.bold else regular
            text.letterSpacing = spacing
            text.textAlign = Paint.Align.LEFT
            text.alpha = 255
        }

        /**
         * One line of text at a baseline. Prose that is too long for [maxWidth] is ellipsized with
         * "…"; numbers pass [fitWidth] instead and are scaled down (never below [MIN_FIT_SIZE]).
         */
        private fun line(
            value: String,
            x: Float,
            baseline: Float,
            size: Float,
            color: Int,
            bold: Boolean = false,
            align: Paint.Align = Paint.Align.LEFT,
            spacing: Float = 0f,
            maxWidth: Float? = null,
            fitWidth: Float? = null,
        ) {
            applyStyle(size, color, bold, spacing)
            text.textAlign = align
            // Money and quantities must never be cut off, so they shrink to fit instead of ellipsizing.
            if (fitWidth != null) {
                val measured = text.measureText(value)
                if (measured > fitWidth) text.textSize = maxOf(MIN_FIT_SIZE, size * fitWidth / measured)
            }
            val shown = if (maxWidth != null) {
                TextUtils.ellipsize(value, text, maxWidth, TextUtils.TruncateAt.END).toString()
            } else {
                value
            }
            canvas.drawText(shown, x, baseline, text)
        }

        private fun rect(l: Float, t: Float, r: Float, b: Float, color: Int) {
            shape.style = Paint.Style.FILL
            shape.color = argb(color)
            canvas.drawRect(l, t, r, b, shape)
        }

        private fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, fill: Int, stroke: Int?) {
            val box = RectF(l, t, r, b)
            shape.style = Paint.Style.FILL
            shape.color = argb(fill)
            canvas.drawRoundRect(box, radius, radius, shape)
            if (stroke != null) {
                shape.style = Paint.Style.STROKE
                shape.strokeWidth = 1f
                shape.color = argb(stroke)
                canvas.drawRoundRect(box, radius, radius, shape)
            }
        }

        private fun argb(rgb: Int, alpha: Int = 255): Int = (alpha shl 24) or rgb
    }

    private companion object {
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val AVATAR_DECODE_PX = 256
    }
}

// Page geometry, in points.
private const val W = 595f
private const val H = 842f
private const val MARGIN = 36f
private const val HEADER_H = 132f
private const val CONT_HEADER_H = 64f
private const val MIN_FIT_SIZE = 6.5f

/** Where the table starts on page 1 (under the billed-to card) and on later pages. */
private const val FIRST_TABLE_TOP = 326f
private const val NEXT_TABLE_TOP = 92f

/** Nothing but the footer goes below this line. */
private const val CONTENT_BOTTOM = H - 110f

private const val TABLE_HEADER_H = 28f
private const val ROW_PADDING = 13f
private const val ROW_MIN_H = 40f
private const val TOTAL_GAP = 20f
private const val TOTAL_H = 88f

// Columns: item 52-252, unit price 262-372, qty 390-434, amount 440-543.
// Numbers shrink to their column rather than overlap a neighbor or lose digits.
private const val ITEM_X = MARGIN + 16f
private const val ITEM_WIDTH = 200f
private const val UNIT_RIGHT = 372f
private const val UNIT_WIDTH = 108f
private const val QTY_CENTER = 412f
private const val QTY_WIDTH = 44f
private const val AMOUNT_RIGHT = W - MARGIN - 16f
private const val AMOUNT_WIDTH = 103f
