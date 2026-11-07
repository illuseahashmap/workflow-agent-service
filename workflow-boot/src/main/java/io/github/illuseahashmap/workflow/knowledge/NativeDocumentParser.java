package io.github.illuseahashmap.workflow.knowledge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Bounded adapters produce citations, not publication decisions. Original bytes never traverse String. */
final class NativeDocumentParser {
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_BLOCKS = 4096;
    private static final int MAX_DERIVED_BYTES = 2 * 1024 * 1024, MAX_BLOCK_CHARS = 8000;
    private final long started = System.nanoTime();
    private int derivedBytes;
    private void checkTime() {
        if (System.nanoTime() - started > java.time.Duration.ofSeconds(10).toNanos())
            throw new IllegalArgumentException("Original parsing exceeded time budget");
    }
    private void charge(String value) {
        checkTime();
        derivedBytes += value.getBytes(StandardCharsets.UTF_8).length + 1;
        if (derivedBytes > MAX_DERIVED_BYTES) throw new IllegalArgumentException("Derived text exceeds total byte budget");
    }
    private void omit(List<String> exclusions, String reason) {
        if (exclusions.size() >= MAX_BLOCKS) throw new IllegalArgumentException("Too many excluded locations");
        charge(reason);
        exclusions.add(reason);
    }
    record Block(NativeLocator anchor, String text) {}
    record Manifest(String status, String recipe, List<Block> blocks, List<String> exclusions) {}
    private static final Parser MARKDOWN = Parser.builder().extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

    static Manifest parse(String format, byte[] bytes) {
        return new NativeDocumentParser().parseOriginal(format, bytes);
    }
    private Manifest parseOriginal(String format, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Original exceeds upload limit or is empty");
        try {
            return switch (format) {
                case "MD" -> markdown(bytes);
                case "XLSX", "DOCX" -> {
                    var parts = inspectZip(bytes,format);
                    boolean workbook = parts.contains("xl/workbook.xml"), word = parts.contains("word/document.xml");
                    if (!parts.contains("[Content_Types].xml") || workbook == word || workbook != format.equals("XLSX"))
                        throw new IllegalArgumentException("Office package and declared format differ");
                    if (parts.stream().anyMatch(p -> p.endsWith(".bin") || p.contains("/externalLinks/")
                            || p.contains("/embeddings/") || p.contains("/drawings/")))
                        yield unavailable(format, "Embedded, linked or drawing content requires review");
                    yield workbook ? excel(bytes) : unavailable(format, "Word parser not enabled");
                }
                case "PDF" -> {
                    if (!new String(bytes,0,Math.min(5,bytes.length),StandardCharsets.US_ASCII).equals("%PDF-"))
                        throw new IllegalArgumentException("Invalid PDF signature");
                    yield unavailable(format, "PDF parser not enabled");
                }
                default -> throw new IllegalArgumentException("Unsupported file extension");
            };
        } catch (IOException | org.apache.poi.ooxml.POIXMLException e) {
            throw new IllegalArgumentException("Malformed or unsupported original", e);
        }
    }
    private static Manifest unavailable(String format, String reason) {
        return new Manifest("NOT_INDEXED",format+"-intake-v1",List.of(),List.of(reason));
    }
    private Set<String> inspectZip(byte[] bytes,String format) throws IOException {
        var names = new HashSet<String>(); long total=0;
        try (var z=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var buf=new byte[8192];
            for (ZipEntry e; (e=z.getNextEntry())!=null;) {
                if (names.size()>=512 || !names.add(e.getName()) || e.getName().contains("..") || e.getName().startsWith("/"))
                    throw new IllegalArgumentException("Unsafe Office archive");
                var types=e.getName().equals("[Content_Types].xml") ? new ByteArrayOutputStream() : null;
                for (int n;(n=z.read(buf))!=-1;) {
                    checkTime();
                    if ((total+=n)>8*1024*1024) throw new IllegalArgumentException("Expanded Office archive exceeds limit");
                    if(types!=null) {
                        if(types.size()+n>65536) throw new IllegalArgumentException("Package manifest exceeds limit");
                        types.write(buf,0,n);
                    }
                }
                if(types!=null) validateOfficeType(types.toByteArray(),format);
            }
        }
        return names;
    }
    private static void validateOfficeType(byte[] manifest,String format) {
        boolean excel=format.equals("XLSX");
        String xmlDefault=null, mainOverride=null;
        String main=excel?"/xl/workbook.xml":"/word/document.xml";
        String mime="application/vnd.openxmlformats-officedocument."
                +(excel?"spreadsheetml.sheet.main+xml":"wordprocessingml.document.main+xml");
        var factory=javax.xml.stream.XMLInputFactory.newDefaultFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        try {
            var reader=factory.createXMLStreamReader(new ByteArrayInputStream(manifest));
            try {
                while(reader.hasNext()) {
                    int event=reader.next();
                    if(event==javax.xml.stream.XMLStreamConstants.DTD)throw new IllegalArgumentException("DTD not allowed");
                    if(event!=javax.xml.stream.XMLStreamConstants.START_ELEMENT ||
                            !"http://schemas.openxmlformats.org/package/2006/content-types".equals(reader.getNamespaceURI())) continue;
                    String type=reader.getAttributeValue(null,"ContentType");
                    if(reader.getLocalName().equals("Override") && main.equals(reader.getAttributeValue(null,"PartName"))) {
                        if(mainOverride!=null || type==null) throw new IllegalArgumentException("Ambiguous Office override");
                        mainOverride=type;
                    } else if(reader.getLocalName().equals("Default") && "xml".equalsIgnoreCase(reader.getAttributeValue(null,"Extension"))) {
                        if(xmlDefault!=null || type==null) throw new IllegalArgumentException("Ambiguous Office default");
                        xmlDefault=type;
                    }
                }
            } finally {reader.close();}
        } catch(javax.xml.stream.XMLStreamException e){throw new IllegalArgumentException("Invalid Office manifest",e);}
        if(!mime.equals(mainOverride==null ? xmlDefault : mainOverride)) throw new IllegalArgumentException("Office main part type mismatch");
    }
    private void append(List<Block> blocks, String kind, String part, String start, String end,String text) {
        if (blocks.size()>=MAX_BLOCKS || text.length()>MAX_BLOCK_CHARS) throw new IllegalArgumentException("Structure exceeds block budget");
        charge(text);
        blocks.add(new Block(new NativeLocator(2,NativeLocator.Kind.valueOf(kind),part,start,end,null),text.strip()));
    }
    private Manifest markdown(byte[] bytes) throws IOException {
        String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text=text.substring(1);
        if (text.chars().anyMatch(c->c<32 && c!='\r' && c!='\n' && c!='\t')) throw new IllegalArgumentException("Not Markdown text");
        String[] lines=text.split("\\r\\n|\\r|\\n",-1);
        var blocks=new ArrayList<Block>(); var omitted=new ArrayList<String>();
        for (Node node=MARKDOWN.parse(text).getFirstChild();node!=null;node=node.getNext()) {
            checkTime();
            var spans=node.getSourceSpans();
            if (spans.isEmpty()) return unavailable("MD","Block has no source location");
            int from=spans.getFirst().getLineIndex(), to=spans.getLast().getLineIndex();
            if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
                omit(omitted,"lines "+(from+1)+"-"+(to+1)+": code, not policy text"); continue;
            }
            if (!(node instanceof Paragraph || node instanceof Heading || node instanceof TableBlock || node instanceof ListBlock)
                    || containsUnsupported(node)) return unavailable("MD","Unsupported markup at line "+(from+1));
            if (node instanceof TableBlock) {
                for (int i=from;i<=to;i++) {
                    if (i==from+1) { omit(omitted,"line "+(i+1)+": table delimiter"); continue; }
                    append(blocks,"MD_LINES","",String.valueOf(i+1),String.valueOf(i+1),lines[i]);
                }
            } else append(blocks,"MD_LINES","",String.valueOf(from+1),String.valueOf(to+1),
                    String.join("\n",Arrays.copyOfRange(lines,from,to+1)));
        }
        return blocks.isEmpty()?unavailable("MD","No eligible text"):new Manifest("PARSED","commonmark-gfm-v1",blocks,omitted);
    }
    private static boolean containsUnsupported(Node n) {
        var remaining = new ArrayDeque<Node>();
        remaining.push(n);
        while (!remaining.isEmpty()) {
            var next = remaining.pop();
            if (next instanceof HtmlInline || next instanceof HtmlBlock || next instanceof Image
                    || next instanceof FencedCodeBlock || next instanceof IndentedCodeBlock) return true;
            for (Node child=next.getFirstChild();child!=null;child=child.getNext()) remaining.push(child);
        }
        return false;
    }
    private Manifest excel(byte[] bytes) throws IOException {
        var blocks=new ArrayList<Block>(); var omitted=new ArrayList<String>();
        try (var w=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var display=new DataFormatter(Locale.ROOT);
            for (int s=0;s<w.getNumberOfSheets();s++) {
                checkTime();
                Sheet sheet=w.getSheetAt(s); String name=sheet.getSheetName();
                if (w.isSheetHidden(s)||w.isSheetVeryHidden(s)) { omit(omitted,name+": hidden sheet"); continue; }
                // Horizontal merges belong to one logical row. Do not guess vertical header inheritance.
                if (sheet.getNumMergedRegions()>4096) throw new IllegalArgumentException("Too many merged ranges");
                Map<Integer,Integer> mergedEnds=new HashMap<>();
                for (CellRangeAddress m:sheet.getMergedRegions()) {
                    if (m.getFirstRow()!=m.getLastRow()) return unavailable("XLSX",name+": vertical merge requires review");
                    mergedEnds.merge(m.getFirstRow(),m.getLastColumn(),Math::max);
                }
                int visited=0;
                for (Row row:sheet) {
                    checkTime();
                    if (++visited>MAX_BLOCKS || row.getLastCellNum()>256) throw new IllegalArgumentException("Worksheet exceeds limit");
                    if (row.getZeroHeight()) { omit(omitted,name+"!"+(row.getRowNum()+1)+": hidden row"); continue; }
                    var values=new StringBuilder(); int first=-1,last=-1;
                    for (Cell cell:row) {
                        int col=cell.getColumnIndex();
                        if (sheet.isColumnHidden(col)) { omit(omitted,name+"!"+cell.getAddress()+": hidden column"); continue; }
                        if (cell.getCellType()==CellType.BLANK) continue;
                        if (cell.getCellType()==CellType.FORMULA || cell.getCellType()==CellType.ERROR)
                            return unavailable("XLSX",name+"!"+cell.getAddress()+": unverified formula or error");
                        String shown=display.formatCellValue(cell);
                        if (shown.isBlank()) continue;
                        String raw=cell instanceof org.apache.poi.xssf.usermodel.XSSFCell x && cell.getCellType()==CellType.NUMERIC ? x.getRawValue() : null;
                        if(first<0) first=col;
                        last=col;
                        if (!values.isEmpty()) values.append(" | ");
                        values.append(cell.getAddress()).append(": ").append(shown);
                        if (raw!=null && !shown.equals(raw)) values.append(" [raw=").append(raw).append(']');
                        if (values.length()>MAX_BLOCK_CHARS) throw new IllegalArgumentException("Structure exceeds block budget");
                    }
                    if(first<0) continue;
                    last=Math.max(last,mergedEnds.getOrDefault(row.getRowNum(),last));
                    if (last>255) throw new IllegalArgumentException("Merged row exceeds column budget");
                    for(int col=first;col<=last;col++) if(sheet.isColumnHidden(col))
                        return unavailable("XLSX",name+": noncontiguous visible selection requires review");
                    append(blocks,"XLSX_CELLS",name,new org.apache.poi.ss.util.CellAddress(row.getRowNum(),first).formatAsString(),
                            new org.apache.poi.ss.util.CellAddress(row.getRowNum(),last).formatAsString(),values.toString());
                }
            }
        }
        return blocks.isEmpty()?unavailable("XLSX","No visible cells"):new Manifest("PARSED","xssf-visible-rows-v1",blocks,omitted);
    }
}
