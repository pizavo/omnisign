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
})


