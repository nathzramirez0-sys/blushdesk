package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerWithOrders
import com.blushdesk.app.data.local.database.ExportSummary
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.domain.model.ExportSnapshot
import com.blushdesk.app.ui.theme.BrandPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Writes the whole database to a formatted .xlsx with five sheets: Items (opens first), Buyers,
 * Orders, Operator and Summary. Runs on [Dispatchers.IO].
 *
 * Apache POI is a desktop-Java library, so a few of its habits are avoided on purpose:
 *  - No `autoSizeColumn`: it measures text with java.awt.font, which Android does not have.
 *    Column widths are fixed instead.
 *  - Formulas only where they earn it (the Items sheet's totals), and POI evaluates them before
 *    saving. Without the stored results, previewers that do not recalculate (mail and Drive
 *    viewers) would show blank totals. The other sheets hold plain values.
 *  - POI's ZIP output is rewritten as a plain ZIP; see [rewriteAsPlainZip] for why Excel on
 *    Android needs that.
 *  - The workbook is written to a `.part` file and renamed, so a failure never leaves a truncated
 *    file that could be shared as if it were good.
 *
 * Nothing here touches Android APIs, so the same class runs in plain JVM unit tests.
 */
class ExcelExporter(private val zone: ZoneId = ZoneId.systemDefault()) {

    /** Builds the workbook for [snapshot] into [destination] and returns it. */
    suspend fun export(destination: File, snapshot: ExportSnapshot): File = withContext(Dispatchers.IO) {
        val folder = destination.absoluteFile.parentFile
        folder?.mkdirs()
        val raw = File(folder, destination.name + ".raw")
        val partial = File(folder, destination.name + ".part")
        try {
            XSSFWorkbook().use { workbook ->
                val styles = Styles(workbook)
                writeItems(workbook.createSheet(SHEET_ITEMS), styles, snapshot.buyers)
                writeBuyers(workbook.createSheet(SHEET_BUYERS), styles, snapshot.buyers)
                writeOrders(workbook.createSheet(SHEET_ORDERS), styles, snapshot.buyers)
                writeOperator(workbook.createSheet(SHEET_OPERATOR), styles, snapshot.operator)
                writeSummary(workbook.createSheet(SHEET_SUMMARY), styles, snapshot)
                // Store each formula's result next to it, for viewers that never calculate.
                XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook)

                workbook.properties.coreProperties.apply {
                    creator = "BlushDesk"
                    title = "${snapshot.operator.storeName.ifBlank { "Showroom" }} export"
                }
                FileOutputStream(raw).use { workbook.write(it) }
            }
            rewriteAsPlainZip(raw, partial)
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        } finally {
            raw.delete()
        }
        destination
    }

    /**
     * POI streams every part of the package into the ZIP with a "data descriptor": the local
     * header says zero for the CRC and sizes, and the real values follow the data. Desktop Excel
     * reads the central directory and does not mind. Excel for Android refuses such a file and
     * calls it password-protected. Copying the entries into a seekable file lets commons-compress
     * go back and write the real CRC and sizes into every local header, the way Excel saves its own
     * files. ZIP64 and the UTF-8 name flag are left off too: an export is far below 4 GB and every
     * part name is ASCII.
     */
    private fun rewriteAsPlainZip(source: File, target: File) {
        ZipFile(source).use { zip ->
            ZipArchiveOutputStream(target).use { out ->
                out.setUseZip64(Zip64Mode.Never)
                out.setUseLanguageEncodingFlag(false)
                for (entry in zip.entries()) {
                    out.putArchiveEntry(
                        ZipArchiveEntry(entry.name).apply {
                            method = ZipEntry.DEFLATED
                            time = entry.time
                        },
                    )
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeArchiveEntry()
                }
            }
        }
    }

    // ---- Worksheet 1: Items -----------------------------------------------------------------

    /**
     * The sheet to read on a tablet: each order's products as Product Name / Quantity / Price /
     * Total Amount, newest order first, each order with its total and a grand total at the end.
     * The totals are formulas, so the sheet still adds up if someone edits a quantity or price in
     * Excel. The grand total adds the rows labelled [ORDER_TOTAL_LABEL].
     */
    private fun writeItems(sheet: XSSFSheet, styles: Styles, buyers: List<BuyerWithOrders>) {
        writeHeader(sheet, styles, ITEM_COLUMNS, widths = listOf(40, 12, 16, 18))
        sheet.createFreezePane(0, 1)
        fitToWidth(sheet, landscape = false)

        val orders = ordersOf(buyers).reversed()
        if (orders.isEmpty()) {
            sheet.createRow(1).text(0, "No orders yet", styles.subtitle)
            return
        }

        var row = 1
        orders.forEach { (buyer, withItems) ->
            val order = withItems.order
            val title = "${Formats.orderNumber(order.id)} · ${buyer.fullName} · ${Formats.date(order.purchaseDateTime, zone)}"
            sheet.createRow(row).apply {
                text(0, title, styles.orderTitle)
                (1..3).forEach { createCell(it).cellStyle = styles.orderTitle }
            }
            sheet.addMergedRegion(CellRangeAddress(row, row, 0, 3))
            row++

            val firstLine = row + 1 // formulas use Excel's 1-based row numbers
            withItems.items.forEach { item ->
                val excelRow = row + 1
                sheet.createRow(row).apply {
                    text(0, item.productName, styles.body(Kind.TEXT, false))
                    number(1, item.quantity.toDouble(), styles.body(Kind.COUNT, false))
                    number(2, Money.toExcelNumber(item.unitPrice), styles.body(Kind.MONEY, false))
                    formula(3, "ROUND(B$excelRow*C$excelRow,2)", styles.body(Kind.MONEY, false))
                }
                row++
            }
            sheet.createRow(row).apply {
                text(2, ORDER_TOTAL_LABEL, styles.orderTotalLabel)
                formula(3, "SUM(D$firstLine:D$row)", styles.body(Kind.MONEY_BOLD, false))
            }
            row += 2 // the total, then a blank row before the next order
        }

        sheet.createRow(row).apply {
            heightInPoints = 22f
            text(0, "GRAND TOTAL", styles.grandTotalLabel)
            (1..2).forEach { createCell(it).cellStyle = styles.grandTotalLabel }
            formula(3, "SUMIF(C2:C$row,\"$ORDER_TOTAL_LABEL\",D2:D$row)", styles.grandTotal)
        }
        sheet.addMergedRegion(CellRangeAddress(row, row, 0, 2))
    }

    // ---- Worksheet 2: Buyers ----------------------------------------------------------------

    private fun writeBuyers(sheet: XSSFSheet, styles: Styles, buyers: List<BuyerWithOrders>) {
        writeHeader(sheet, styles, BUYER_COLUMNS, widths = listOf(10, 26, 18, 24, 30, 16, 18))
        buyers.forEachIndexed { index, (buyer, orders) ->
            val zebra = index % 2 == 1
            sheet.createRow(index + 1).apply {
                number(0, buyer.id.toDouble(), styles.body(Kind.ID, zebra))
                text(1, buyer.fullName, styles.body(Kind.TEXT, zebra))
                text(2, buyer.contactNumber, styles.body(Kind.TEXT, zebra))
                text(3, buyer.facebookName, styles.body(Kind.TEXT, zebra))
                text(4, buyer.email, styles.body(Kind.TEXT, zebra))
                createCell(5).apply {
                    setCellValue(buyer.dateAdded.atZone(zone).toLocalDate())
                    cellStyle = styles.body(Kind.DATE, zebra)
                }
                number(6, orders.size.toDouble(), styles.body(Kind.COUNT, zebra))
            }
        }
        finishTable(sheet, BUYER_COLUMNS.size, buyers.size)
    }

    // ---- Worksheet 3: Orders ----------------------------------------------------------------

    /**
     * One row per product line, so an order with three products takes three rows sharing the same
     * Order ID. "Total Amount" is that line's unit price x quantity; "Order Total" repeats the
     * whole order's total on each of its rows. Zebra striping alternates per order, not per row.
     */
    private fun writeOrders(sheet: XSSFSheet, styles: Styles, buyers: List<BuyerWithOrders>) {
        writeHeader(sheet, styles, ORDER_COLUMNS, widths = listOf(10, 10, 24, 32, 15, 10, 16, 15, 14, 17, 16, 18, 16))
        val orders = ordersOf(buyers)

        var rowIndex = 1
        orders.forEachIndexed { orderIndex, (buyer, withItems) ->
            val order = withItems.order
            val zebra = orderIndex % 2 == 1
            val purchased = order.purchaseDateTime.atZone(zone)
            withItems.items.forEach { item -> sheet.createRow(rowIndex++).apply {
                number(0, order.id.toDouble(), styles.body(Kind.ID, zebra))
                number(1, buyer.id.toDouble(), styles.body(Kind.ID, zebra))
                text(2, buyer.fullName, styles.body(Kind.TEXT, zebra))
                text(3, item.productName, styles.body(Kind.TEXT, zebra))
                number(4, Money.toExcelNumber(item.unitPrice), styles.body(Kind.MONEY, zebra))
                number(5, item.quantity.toDouble(), styles.body(Kind.COUNT, zebra))
                number(6, Money.toExcelNumber(item.lineTotal), styles.body(Kind.MONEY, zebra))
                createCell(7).apply {
                    setCellValue(purchased.toLocalDate())
                    cellStyle = styles.body(Kind.DATE, zebra)
                }
                // An Excel time is the fraction of a day.
                number(8, purchased.toLocalTime().toSecondOfDay() / SECONDS_PER_DAY, styles.body(Kind.TIME, zebra))
                text(9, order.paymentMode.label, styles.body(Kind.TEXT, zebra))
                text(10, order.paymentStatus.label, styles.badge(BrandPalette.tone(order.paymentStatus)))
                text(11, order.fulfillmentStatus.label, styles.badge(BrandPalette.tone(order.fulfillmentStatus)))
                number(12, Money.toExcelNumber(order.totalAmount), styles.body(Kind.MONEY_BOLD, zebra))
            } }
        }
        finishTable(sheet, ORDER_COLUMNS.size, rowIndex - 1)
    }

    // ---- Worksheet 4: Operator --------------------------------------------------------------

    private fun writeOperator(sheet: XSSFSheet, styles: Styles, operator: OperatorProfile) {
        writeHeader(sheet, styles, OPERATOR_COLUMNS, widths = listOf(26, 28, 30, 18))
        sheet.createRow(1).apply {
            text(0, operator.fullName, styles.body(Kind.TEXT, false))
            text(1, operator.storeName, styles.body(Kind.TEXT, false))
            text(2, operator.email, styles.body(Kind.TEXT, false))
            text(3, operator.phoneNumber, styles.body(Kind.TEXT, false))
        }
        sheet.createFreezePane(0, 1)
        fitToWidth(sheet)
    }

    // ---- Worksheet 5: Summary ---------------------------------------------------------------

    private fun writeSummary(sheet: XSSFSheet, styles: Styles, snapshot: ExportSnapshot) {
        sheet.setColumnWidth(0, 30 * 256)
        sheet.setColumnWidth(1, 22 * 256)
        sheet.setDisplayGridlines(false)

        val store = snapshot.operator.storeName.ifBlank { "Showroom" }
        sheet.createRow(0).apply {
            heightInPoints = 30f
            text(0, "$store - Summary", styles.title)
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 1))
        sheet.createRow(1).text(0, "Generated ${Formats.dateTime(snapshot.takenAt, zone)}", styles.subtitle)

        val headerRow = SUMMARY_HEADER_ROW
        sheet.createRow(headerRow).apply {
            heightInPoints = 22f
            text(0, "Metric", styles.header)
            text(1, "Value", styles.header)
        }
        summaryLines(snapshot.summary).forEachIndexed { index, (label, value) ->
            val zebra = index % 2 == 1
            sheet.createRow(headerRow + 1 + index).apply {
                text(0, label, styles.label(zebra))
                when (value) {
                    is SummaryValue.Count -> number(1, value.count.toDouble(), styles.body(Kind.COUNT, zebra))
                    is SummaryValue.Amount -> number(1, Money.toExcelNumber(value.amount), styles.body(Kind.MONEY_BOLD, zebra))
                }
            }
        }
        fitToWidth(sheet)
    }

    private sealed interface SummaryValue {
        data class Count(val count: Int) : SummaryValue
        data class Amount(val amount: java.math.BigDecimal) : SummaryValue
    }

    private fun summaryLines(s: ExportSummary): List<Pair<String, SummaryValue>> = listOf(
        "Total Buyers" to SummaryValue.Count(s.totalBuyers),
        "Total Orders" to SummaryValue.Count(s.totalOrders),
        "Paid Orders" to SummaryValue.Count(s.paidOrders),
        "Unpaid Orders" to SummaryValue.Count(s.unpaidOrders),
        "Pending Orders" to SummaryValue.Count(s.pendingOrders),
        "Processing Orders" to SummaryValue.Count(s.processingOrders),
        "Preparing Orders" to SummaryValue.Count(s.preparingOrders),
        "Delivered Orders" to SummaryValue.Count(s.deliveredOrders),
        "Total Recorded Sales" to SummaryValue.Amount(s.totalRecordedSales),
    )

    // ---- Shared sheet helpers ---------------------------------------------------------------

    /** Every order with its buyer, oldest first (ties broken by id, so the order is stable). */
    private fun ordersOf(buyers: List<BuyerWithOrders>): List<Pair<Buyer, OrderWithItems>> = buyers
        .flatMap { entry -> entry.orders.map { entry.buyer to it } }
        .sortedWith(compareBy({ it.second.order.purchaseDateTime }, { it.second.order.id }))

    private fun writeHeader(sheet: XSSFSheet, styles: Styles, titles: List<String>, widths: List<Int>) {
        val header = sheet.createRow(0)
        header.heightInPoints = 24f
        titles.forEachIndexed { index, title ->
            header.text(index, title, styles.header)
            sheet.setColumnWidth(index, widths[index] * 256)
        }
    }

    /** Freeze the header, add filter dropdowns and print landscape on one page width. */
    private fun finishTable(sheet: XSSFSheet, columnCount: Int, dataRows: Int) {
        sheet.createFreezePane(0, 1)
        sheet.setAutoFilter(CellRangeAddress(0, maxOf(dataRows, 1), 0, columnCount - 1))
        fitToWidth(sheet)
    }

    private fun fitToWidth(sheet: XSSFSheet, landscape: Boolean = true) {
        sheet.printSetup.landscape = landscape
        sheet.fitToPage = true
        sheet.printSetup.fitWidth = 1
        sheet.printSetup.fitHeight = 0
    }

    private fun XSSFRow.text(col: Int, value: String, style: XSSFCellStyle) {
        createCell(col).apply {
            setCellValue(value)
            cellStyle = style
        }
    }

    private fun XSSFRow.number(col: Int, value: Double, style: XSSFCellStyle) {
        createCell(col).apply {
            setCellValue(value)
            cellStyle = style
        }
    }

    /** [formula] is written without the leading "=", as POI expects. */
    private fun XSSFRow.formula(col: Int, formula: String, style: XSSFCellStyle) {
        createCell(col).apply {
            cellFormula = formula
            cellStyle = style
        }
    }

    private enum class Kind { TEXT, ID, COUNT, MONEY, MONEY_BOLD, DATE, TIME }

    /**
     * Cell styles are workbook-wide objects with a hard cap (64,000 in .xlsx), so each distinct
     * look is created once and reused for every cell that needs it.
     */
    private class Styles(private val workbook: XSSFWorkbook) {
        private val cache = HashMap<String, XSSFCellStyle>()
        private val formats = workbook.createDataFormat()

        val title = cached("title") {
            setFont(font(size = 18, bold = true, color = BrandPalette.DEEP_MAGENTA))
            verticalAlignment = VerticalAlignment.CENTER
        }
        val subtitle = cached("subtitle") {
            setFont(font(size = 10, italic = true, color = BrandPalette.MUTED_TEXT))
        }
        val header = cached("header") {
            setFont(font(size = 11, bold = true, color = BrandPalette.WHITE))
            fill(BrandPalette.VIBRANT_ROSE)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
            border()
        }

        val orderTitle = cached("order-title") {
            setFont(font(bold = true, color = BrandPalette.DEEP_MAGENTA))
            fill(BrandPalette.LAVENDER_BLUSH)
            verticalAlignment = VerticalAlignment.CENTER
            border()
        }
        val orderTotalLabel = cached("order-total-label") {
            setFont(font(bold = true, color = BrandPalette.DEEP_MAGENTA))
            alignment = HorizontalAlignment.RIGHT
        }
        val grandTotalLabel = cached("grand-total-label") {
            setFont(font(size = 12, bold = true, color = BrandPalette.WHITE))
            fill(BrandPalette.VIBRANT_ROSE)
            verticalAlignment = VerticalAlignment.CENTER
            border()
        }
        val grandTotal = cached("grand-total") {
            setFont(font(size = 12, bold = true, color = BrandPalette.WHITE))
            fill(BrandPalette.VIBRANT_ROSE)
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat = formats.getFormat("\"${Money.SYMBOL}\"#,##0.00")
            border()
        }

        fun label(zebra: Boolean): XSSFCellStyle = cached("label-$zebra") {
            setFont(font(bold = true, color = BrandPalette.DEEP_MAGENTA))
            if (zebra) fill(BrandPalette.LAVENDER_BLUSH)
            border()
        }

        fun body(kind: Kind, zebra: Boolean): XSSFCellStyle = cached("body-$kind-$zebra") {
            setFont(font(bold = kind == Kind.MONEY_BOLD))
            if (zebra) fill(BrandPalette.LAVENDER_BLUSH)
            verticalAlignment = VerticalAlignment.CENTER
            border()
            when (kind) {
                Kind.TEXT -> Unit
                Kind.ID -> dataFormat = formats.getFormat("0")
                Kind.COUNT -> dataFormat = formats.getFormat("#,##0")
                Kind.MONEY, Kind.MONEY_BOLD -> dataFormat = formats.getFormat("\"${Money.SYMBOL}\"#,##0.00")
                Kind.DATE -> dataFormat = formats.getFormat("d mmm yyyy")
                Kind.TIME -> dataFormat = formats.getFormat("h:mm AM/PM")
            }
            if (kind == Kind.DATE || kind == Kind.TIME || kind == Kind.ID) alignment = HorizontalAlignment.LEFT
        }

        fun badge(tone: BrandPalette.Tone): XSSFCellStyle = cached("badge-${tone.foreground}-${tone.background}") {
            setFont(font(bold = true, color = tone.foreground))
            fill(tone.background)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            border()
        }

        private fun cached(key: String, build: XSSFCellStyle.() -> Unit): XSSFCellStyle =
            cache.getOrPut(key) { workbook.createCellStyle().apply(build) }

        private fun font(
            size: Int = 11,
            bold: Boolean = false,
            italic: Boolean = false,
            color: Int = BrandPalette.DARK_TEXT,
        ): XSSFFont = workbook.createFont().apply {
            fontHeightInPoints = size.toShort()
            this.bold = bold
            this.italic = italic
            setColor(rgb(color))
        }

        private fun XSSFCellStyle.fill(rgb: Int) {
            setFillForegroundColor(rgb(rgb))
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        private fun XSSFCellStyle.border() {
            val color = rgb(BrandPalette.OUTLINE_SOFT)
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
            setTopBorderColor(color)
            setBottomBorderColor(color)
            setLeftBorderColor(color)
            setRightBorderColor(color)
        }

        private fun rgb(value: Int) = XSSFColor(
            byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte()),
            null,
        )
    }

    companion object {
        const val SHEET_ITEMS = "Items"
        const val SHEET_BUYERS = "Buyers"
        const val SHEET_ORDERS = "Orders"
        const val SHEET_OPERATOR = "Operator"
        const val SHEET_SUMMARY = "Summary"

        /** Row index of the "Metric | Value" header on the Summary sheet (after title and timestamp). */
        const val SUMMARY_HEADER_ROW = 3

        /** Not in the original specification: asked for later, to read orders on a tablet. */
        val ITEM_COLUMNS = listOf("Product Name", "Quantity", "Price", "Total Amount")

        /** Marks each order's total row on the Items sheet; the grand total's SUMIF looks for it. */
        const val ORDER_TOTAL_LABEL = "Order total"

        val BUYER_COLUMNS = listOf(
            "Buyer ID", "Full Name", "Contact Number",
            // Not in the original specification: the alternative to a contact number.
            "Facebook Name",
            "Email", "Date Added", "Number of Orders",
        )

        val ORDER_COLUMNS = listOf(
            "Order ID", "Buyer ID", "Buyer Name", "Product", "Unit Price", "Quantity", "Total Amount",
            "Purchase Date", "Purchase Time", "Payment Mode", "Payment Status", "Fulfillment Status",
            // Not in the original specification: added with multi-product orders.
            "Order Total",
        )

        val OPERATOR_COLUMNS = listOf("Operator Name", "Store Name", "Email", "Phone Number")

        private const val SECONDS_PER_DAY = 86_400.0
    }
}
