package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class NativeDocumentParserTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"del","ins","vanish","fldSimple","txbxContent","vMerge","drawing"})
    void unsupportedWordContentCannotLeaveAnIndexablePartialPolicy(String element) throws Exception {
        byte[] bytes=wordVariant("word/document.xml","</w:body>","<w:"+element+"/>",null);
        var m=NativeDocumentParser.parse("DOCX",bytes);
        assertThat(m.status()).isEqualTo("NOT_INDEXED");
        assertThat(m.blocks()).isEmpty();
        assertThat(m.exclusions().getFirst()).contains(element);
    }
    @Test void inheritedHiddenStyleBlocksWordAndCommentsAreNotEvidence() throws Exception {
        var hidden=wordVariant("word/styles.xml","</w:styles>","<w:style><w:rPr><w:vanish/></w:rPr></w:style>",null);
        assertThat(NativeDocumentParser.parse("DOCX",hidden).blocks()).isEmpty();
        var comments=wordVariant("word/document.xml","</w:body>","","word/comments.xml");
        var m=NativeDocumentParser.parse("DOCX",comments);
        assertThat(m.status()).isEqualTo("PARSED");
        assertThat(m.exclusions()).contains("Word comments are not policy text");
        assertThat(m.blocks()).noneMatch(b->b.text().contains("评论要求强制审批"));
    }
    private static byte[] wordVariant(String part,String before,String insert,String extra) throws IOException {
        var path=java.nio.file.Path.of("../docs/fixtures/knowledge/pilots/procurement-approval-v1/sources/approval.docx");
        var out=new ByteArrayOutputStream();
        try(var in=new ZipInputStream(java.nio.file.Files.newInputStream(path));var zip=new ZipOutputStream(out)){
            for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry()){
                zip.putNextEntry(new ZipEntry(entry.getName()));
                var data=in.readAllBytes();
                if(entry.getName().equals(part)) {
                    String xml=new String(data,StandardCharsets.UTF_8);
                    assertThat(xml).contains(before);
                    data=xml.replace(before,insert+before).getBytes(StandardCharsets.UTF_8);
                }
                zip.write(data);zip.closeEntry();
            }
            if(extra!=null){zip.putNextEntry(new ZipEntry(extra));zip.write("<comments>评论要求强制审批</comments>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        return out.toByteArray();
    }
    @Test void markdownNeverIndexesCodeOrHTMLAsPolicy() {
        var m=NativeDocumentParser.parse("MD","\uFEFF# 开始\r\n\r\n```md\r\n|委员会必须审批|\r\n```\r\n\r\n条件未知。".getBytes(StandardCharsets.UTF_8));
        assertThat(m.blocks()).extracting(b->b.anchor().start()).containsExactly("1","7");
        assertThat(m.exclusions()).containsExactly("lines 3-5: code, not policy text");
        assertThat(NativeDocumentParser.parse("MD","<table><tr><td>must</td></tr></table>".getBytes()).blocks()).isEmpty();
        assertThatThrownBy(()->NativeDocumentParser.parse("MD",new byte[]{(byte)0xff})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void formulasAndHiddenCellsCannotSupplyWorkflowConditions() throws Exception {
        try(var w=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){
            var sheet=w.createSheet("policy");
            var r=sheet.createRow(0);r.createCell(0).setCellValue("可见");r.createCell(1).setCellValue("隐藏");
            sheet.setColumnHidden(1,true);
            var hidden=sheet.createRow(1);hidden.setZeroHeight(true);hidden.createCell(0).setCellValue("不应检索");
            var out=new ByteArrayOutputStream();w.write(out);
            var m=NativeDocumentParser.parse("XLSX",out.toByteArray());
            assertThat(m.blocks()).hasSize(1);
            assertThat(m.blocks().getFirst().text()).contains("可见").doesNotContain("隐藏","不应检索");
            assertThat(m.exclusions()).hasSize(2);
            r.createCell(2).setCellFormula("1+2");out.reset();w.write(out);
            var blocked=NativeDocumentParser.parse("XLSX",out.toByteArray());
            assertThat(blocked.status()).isEqualTo("NOT_INDEXED");assertThat(blocked.blocks()).isEmpty();
        }
    }
    @Test void sharedStringsCannotAmplifySmallWorkbookIntoUnboundedDerivedText() throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            String shared = "采购".repeat(1000);
            for (int i=0; i<400; i++) sheet.createRow(i).createCell(0).setCellValue(shared);
            workbook.write(out);
            assertThat(out.size()).isLessThan(32 * 1024);
            assertThatThrownBy(() -> NativeDocumentParser.parse("XLSX",out.toByteArray()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total byte budget");
        }
    }
    @Test void boundsSourceLocationsAndRejectsWrongContainersAndExpansionBombs() throws Exception {
        assertThatThrownBy(()->new NativeLocator.Region(1,.8,.2,.4,.9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NativeLocator(2,NativeLocator.Kind.XLSX_CELLS,"sheet","B4","A1",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NativeDocumentParser.parse("PDF","not a pdf".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NativeDocumentParser.parse("DOCX",new byte[]{1,2,3})).isInstanceOf(IllegalArgumentException.class);
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("xl/workbook.xml"));zip.write(new byte[9*1024*1024]);}
        assertThatThrownBy(()->NativeDocumentParser.parse("XLSX",out.toByteArray())).isInstanceOf(IllegalArgumentException.class);
    }
}
