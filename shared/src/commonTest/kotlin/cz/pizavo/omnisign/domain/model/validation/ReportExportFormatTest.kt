package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Verifies [ReportExportFormat] properties and the mapping to [RawReportFormat].
 */
class ReportExportFormatTest : FunSpec({

    test("TXT and JSON have no rawReportFormat") {
        ReportExportFormat.TXT.rawReportFormat.shouldBeNull()
        ReportExportFormat.JSON.rawReportFormat.shouldBeNull()
    }

    test("XML formats map to the correct RawReportFormat") {
        ReportExportFormat.XML_DETAILED.rawReportFormat shouldBe RawReportFormat.XML_DETAILED
        ReportExportFormat.XML_SIMPLE.rawReportFormat shouldBe RawReportFormat.XML_SIMPLE
        ReportExportFormat.XML_DIAGNOSTIC.rawReportFormat shouldBe RawReportFormat.XML_DIAGNOSTIC
        ReportExportFormat.XML_ETSI.rawReportFormat shouldBe RawReportFormat.XML_ETSI
    }

    test("every entry has a non-blank label and description") {
        ReportExportFormat.entries.forEach { format ->
            format.label.shouldNotBeBlank()
            format.description.shouldNotBeBlank()
        }
    }

    test("every entry has a non-blank extension") {
        ReportExportFormat.entries.forEach { format ->
            format.extension.shouldNotBeBlank()
        }
    }

    test("extension values match expected conventions") {
        ReportExportFormat.TXT.extension shouldBe "txt"
        ReportExportFormat.JSON.extension shouldBe "json"
        ReportExportFormat.XML_DETAILED.extension shouldBe "xml"
        ReportExportFormat.XML_SIMPLE.extension shouldBe "xml"
        ReportExportFormat.XML_DIAGNOSTIC.extension shouldBe "xml"
        ReportExportFormat.XML_ETSI.extension shouldBe "xml"
    }

    test("all six formats are present") {
        ReportExportFormat.entries.size shouldBe 6
    }

    test("fileStem is 'report' for TXT and JSON and the variant name for XML") {
        ReportExportFormat.TXT.fileStem shouldBe "report"
        ReportExportFormat.JSON.fileStem shouldBe "report"
        ReportExportFormat.XML_SIMPLE.fileStem shouldBe "simple"
        ReportExportFormat.XML_DETAILED.fileStem shouldBe "detailed"
        ReportExportFormat.XML_DIAGNOSTIC.fileStem shouldBe "diagnostic"
        ReportExportFormat.XML_ETSI.fileStem shouldBe "etsi"
    }

    test("suggestedBaseName drops the source extension and appends the file stem") {
        ReportExportFormat.TXT.suggestedBaseName("contract.pdf") shouldBe "contract.report"
        ReportExportFormat.JSON.suggestedBaseName("contract.pdf") shouldBe "contract.report"
        ReportExportFormat.XML_SIMPLE.suggestedBaseName("contract.pdf") shouldBe "contract.simple"
    }

    test("suggestedBaseName falls back to validation-report for a blank name") {
        ReportExportFormat.TXT.suggestedBaseName("") shouldBe "validation-report.report"
    }

    test("the four XML variants yield distinct file names for one document") {
        val names = listOf(
            ReportExportFormat.XML_SIMPLE,
            ReportExportFormat.XML_DETAILED,
            ReportExportFormat.XML_DIAGNOSTIC,
            ReportExportFormat.XML_ETSI,
        ).map { "${it.suggestedBaseName("doc.pdf")}.${it.extension}" }
        names.toSet().size shouldBe 4
    }
})


