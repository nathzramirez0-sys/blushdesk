package com.blushdesk.app.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.TestData
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Apache POI is a desktop-Java library. The JVM unit test proves the workbook's content; this one
 * proves the same code runs on Android's runtime (ART), which lacks java.awt, StAX and other
 * classes POI normally takes for granted.
 */
@RunWith(AndroidJUnit4::class)
class ExcelExporterAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun exportSample(): File = runBlocking {
        ExcelExporter().export(File(context.cacheDir, "instrumented-export.xlsx"), TestData.sampleSnapshot())
    }

    @Test
    fun writes_a_plain_xlsx_package_with_five_worksheets() {
        val file = exportSample()
        assertTrue(file.length() > 2_000)
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("workbook part", "xl/workbook.xml" in entries)
            assertTrue("styles part", "xl/styles.xml" in entries)
            assertTrue("five worksheets", (1..5).all { "xl/worksheets/sheet$it.xml" in entries })
        }
        // The rewrite to a plain ZIP also runs on ART: no data-descriptor flag in the first header.
        val head = file.inputStream().use { it.readNBytes(8) }
        assertEquals(0, head[6].toInt() and 0x08)
    }

    @Test
    fun reads_back_on_android_with_the_specified_sheets_and_values() {
        exportSample().inputStream().use { input ->
            XSSFWorkbook(input).use { workbook ->
                assertEquals(listOf("Items", "Buyers", "Orders", "Operator", "Summary"), (0 until workbook.numberOfSheets).map { workbook.getSheetName(it) })

                val orders = workbook.getSheet("Orders")
                assertEquals(4, orders.lastRowNum) // one row per product line: 1 + 2 + 1
                assertEquals("Throw Pillow", orders.getRow(4).getCell(3).stringCellValue)
                assertEquals(5_300.00, orders.getRow(4).getCell(12).numericCellValue, 0.0) // that order's total
                assertEquals("Velvet Sofa", orders.getRow(1).getCell(3).stringCellValue)
                assertEquals(25_001.00, orders.getRow(1).getCell(6).numericCellValue, 0.0)
                assertEquals("Paid", orders.getRow(1).getCell(10).stringCellValue)
                assertEquals("Delivered", orders.getRow(1).getCell(11).stringCellValue)

                // POI's formula evaluator ran on ART too: the grand total's stored result is the sum of every line.
                val items = workbook.getSheet("Items")
                val grand = items.getRow(items.lastRowNum)
                assertEquals("GRAND TOTAL", grand.getCell(0).stringCellValue)
                val lineTotals = (1..orders.lastRowNum).sumOf { orders.getRow(it).getCell(6).numericCellValue }
                assertEquals(lineTotals, grand.getCell(3).numericCellValue, 1e-6)

                assertEquals("Ana Reyes", workbook.getSheet("Buyers").getRow(1).getCell(1).stringCellValue)
                assertEquals("Payment Status", workbook.getSheet("Buyers").getRow(0).getCell(7).stringCellValue)
                assertTrue(workbook.getSheet("Buyers").getRow(1).getCell(7).stringCellValue in setOf("Paid", "Not Yet Paid", "No orders"))
                assertEquals("Lia Santos", workbook.getSheet("Operator").getRow(1).getCell(0).stringCellValue)
            }
        }
    }

    @Test
    fun the_peso_sign_survives_into_the_number_format() {
        exportSample().inputStream().use { input ->
            XSSFWorkbook(input).use { workbook ->
                val format = workbook.getSheet("Orders").getRow(1).getCell(4).cellStyle.dataFormatString
                assertTrue(format, format.contains("₱"))
            }
        }
    }

    @Test
    fun exporting_twice_replaces_the_file_and_leaves_no_partial_copy() {
        exportSample()
        val file = exportSample()
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, file.name + ".part").exists())
    }
}
