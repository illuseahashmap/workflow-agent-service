package io.github.illuseahashmap.workflow.knowledge;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.stream.*;

/** Strict WordprocessingML subset: preserve body order and row boundaries, never flatten
 * unknown OOXML containers (in particular revision alternatives) into policy text. */
final class NativeWordParser {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MAIN = "word/document.xml";
    private static final Set<String> SUPPORTED = Set.of(("document body p r t tbl tr tc pPr rPr sectPr tblPr tblGrid tcPr trPr "+
            "pStyle rStyle tblStyle b i u sz szCs color spacing jc rFonts lang ind keepNext keepLines outlineLvl "+
            "pgSz pgMar cols docGrid tblW tblBorders tblLayout tblLook gridCol tcW top bottom left right insideH insideV "+
            "shd vAlign commentReference commentRangeStart commentRangeEnd bookmarkStart bookmarkEnd").split(" "));
    private final NativeDocumentParser budget;
    private final long started = System.nanoTime();
    private int elements;
    private final List<NativeDocumentParser.Block> blocks = new ArrayList<>();
    private final List<String> exclusions = new ArrayList<>();
    private boolean comment;
    private final Set<String> usedStyles=new HashSet<>(), numberedStyles=new HashSet<>();
    private final Map<String,String> styleParents=new HashMap<>();

    NativeWordParser(NativeDocumentParser budget) { this.budget = budget; }
    private static final class Unsupported extends RuntimeException {
        Unsupported(String message) { super(message); }
    }
    NativeDocumentParser.Manifest parse(byte[] bytes) throws IOException {
        var factory=XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        try (var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            boolean mainRelationship=false;
            for (var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                String part=entry.getName();
                if (part.matches("word/(header|footer|footnotes|endnotes|media/|glossary/).*"))
                    throw new Unsupported("Additional Word content requires review");
                if (part.equals("word/comments.xml")) comment=true;
                if (!part.equals(MAIN) && !part.equals("word/styles.xml") && !part.equals("word/stylesWithEffects.xml")
                        && !part.endsWith(".rels")) continue;
                var reader=factory.createXMLStreamReader(new ByteArrayInputStream(zip.readAllBytes()));
                try {
                    if(part.equals(MAIN)) body(reader);
                    else if(part.equals("word/styles.xml") || part.equals("word/stylesWithEffects.xml")) inspectStyles(reader);
                    else while(reader.hasNext()) {
                        int event=next(reader);
                        if(event!=XMLStreamConstants.START_ELEMENT) continue;
                        String name=reader.getLocalName();
                        if("http://schemas.openxmlformats.org/package/2006/relationships".equals(reader.getNamespaceURI()) && name.equals("Relationship")) {
                            if("External".equalsIgnoreCase(reader.getAttributeValue(null,"TargetMode")))
                                throw new Unsupported("External Word relationship requires review");
                            if(part.equals("_rels/.rels") && "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                                    .equals(reader.getAttributeValue(null,"Type"))) {
                                if(mainRelationship || !Set.of(MAIN,"/"+MAIN).contains(Objects.toString(reader.getAttributeValue(null,"Target"),"")))
                                    throw new Unsupported("Ambiguous Word main document");
                                mainRelationship=true;
                            }
                        }
                    }
                } finally { reader.close(); }
            }
            if(!mainRelationship || blocks.isEmpty()) throw new Unsupported("No supported Word body");
            for(String id:usedStyles) {
                var seen=new HashSet<String>();
                for(String style=id;style!=null;style=styleParents.get(style))
                    if(!seen.add(style) || seen.size()>64 || numberedStyles.contains(style))
                        throw new Unsupported("Inherited Word numbering or ambiguous style requires review");
            }
            if(comment) exclusions.add("Word comments are not policy text");
            return new NativeDocumentParser.Manifest("PARSED","word-body-rows-v2",blocks,exclusions);
        } catch (Unsupported e) { return NativeDocumentParser.unavailable("DOCX",e.getMessage()); }
        catch (XMLStreamException e) { throw new IllegalArgumentException("Malformed Word package",e); }
    }
    private void inspectStyles(XMLStreamReader reader) throws XMLStreamException {
        String style=null;
        while(reader.hasNext()) {
            int event=next(reader);
            if(event==XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("style")) style=null;
            if(event!=XMLStreamConstants.START_ELEMENT) continue;
            switch(reader.getLocalName()) {
                case "style" -> {
                    style=reader.getAttributeValue(W,"styleId");
                    if(style==null) throw new Unsupported("Unknown Word style");
                    if(Set.of("true","1","on").contains(Objects.toString(reader.getAttributeValue(W,"default"),""))) usedStyles.add(style);
                }
                case "basedOn" -> {
                    String parent=reader.getAttributeValue(W,"val");
                    if(style!=null && parent!=null) {
                        String previous=styleParents.putIfAbsent(style,parent);
                        if(previous!=null && !previous.equals(parent)) throw new Unsupported("Ambiguous Word style");
                    }
                }
                case "numPr" -> {
                    if(style==null) throw new Unsupported("Default Word numbering requires review");
                    numberedStyles.add(style);
                }
                case "vanish","webHidden","specVanish","rPrChange","pPrChange" ->
                        throw new Unsupported("Hidden or revised Word style requires review");
            }
        }
    }
    private int next(XMLStreamReader reader) throws XMLStreamException {
        int event=reader.next();
        if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)
            throw new IllegalArgumentException("Unsafe Word XML");
        if(event==XMLStreamConstants.START_ELEMENT && ++elements>150_000 ||
                System.nanoTime()-started>10_000_000_000L) throw new IllegalArgumentException("Word parsing exceeded budget");
        return event;
    }
    private void body(XMLStreamReader reader) throws XMLStreamException {
        var stack=new ArrayDeque<String>();
        int para=0,table=0,row=0,cell=0;
        String anchor=null;
        var text=new StringBuilder();
        while(reader.hasNext()) {
            int e=next(reader);
            if(e==XMLStreamConstants.START_ELEMENT) {
                String n=reader.getLocalName(), parent=stack.peek();
                if(!W.equals(reader.getNamespaceURI()) || !SUPPORTED.contains(n) || stack.size()>64)
                    throw new Unsupported("Unsupported Word structure: "+n);
                boolean legal=switch(n) {
                    case "document" -> parent==null;
                    case "body" -> "document".equals(parent);
                    case "tbl" -> "body".equals(parent);
                    case "tr" -> "tbl".equals(parent);
                    case "tc" -> "tr".equals(parent);
                    case "p" -> "body".equals(parent)||"tc".equals(parent);
                    case "r" -> "p".equals(parent);
                    case "t" -> "r".equals(parent);
                    default -> true;
                };
                if(!legal) throw new Unsupported("Unsupported Word nesting: "+n);
                if(n.equals("pStyle") || n.equals("rStyle") || n.equals("tblStyle")) {
                    String id=reader.getAttributeValue(W,"val");
                    if(id==null || id.isBlank()) throw new Unsupported("Missing Word style reference");
                    usedStyles.add(id);
                }
                if(n.equals("p") && "body".equals(parent)) {anchor="body/p["+(++para)+"]";text.setLength(0);}
                if(n.equals("tbl")) {table++;row=0;}
                if(n.equals("tr")) {anchor="body/tbl["+table+"]/tr["+(++row)+"]";cell=0;text.setLength(0);}
                if(n.equals("tc") && ++cell>1) text.append(" | ");
                if(n.startsWith("comment")) comment=true;
                stack.push(n);
            } else if(e==XMLStreamConstants.CHARACTERS || e==XMLStreamConstants.CDATA) {
                if("t".equals(stack.peek())) text.append(reader.getText());
                else if(!reader.isWhiteSpace()) throw new Unsupported("Unexpected Word text");
            } else if(e==XMLStreamConstants.END_ELEMENT) {
                String n=stack.pop();
                if((n.equals("p") && "body".equals(stack.peek())) || n.equals("tr")) {
                    if(!text.toString().isBlank()) budget.append(blocks,"WORD_BLOCKS",MAIN,anchor,anchor,text.toString(),n.equals("tr")?cell:0);
                } else if(n.equals("p") && "tc".equals(stack.peek())) text.append(' ');
            }
            if(text.length()>8000) throw new IllegalArgumentException("Word block exceeds budget");
        }
    }
}
