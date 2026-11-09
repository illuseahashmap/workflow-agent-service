package io.github.illuseahashmap.workflow.knowledge;

import java.util.Objects;

/** Native-source locator v2. The owning original SHA and recipe are part of its preview envelope.
 * Office/Markdown are not paginated; PDF regions use a top-left origin and normalized coordinates. */
public record NativeLocator(int version, Kind kind, String part, String start, String end, Region region) {
    public enum Kind { MD_LINES, XLSX_CELLS, WORD_BLOCKS, PDF_REGION }
    public record Region(int page, double left, double top, double right, double bottom) {
        public Region {
            if (page < 1 || !(0<=left && left<right && right<=1 && 0<=top && top<bottom && bottom<=1))
                throw new IllegalArgumentException("Invalid PDF region");
        }
    }
    public NativeLocator {
        Objects.requireNonNull(kind);
        if(version!=2) throw new IllegalArgumentException("Unsupported native locator");
        boolean valid=switch(kind) {
            case MD_LINES -> region==null && "".equals(part) && line(start)>0 && line(end)>=line(start);
            case XLSX_CELLS -> region==null && part!=null && !part.isBlank() && cellRange(start,end);
            case WORD_BLOCKS -> region==null && "word/document.xml".equals(part) && start!=null && start.equals(end)
                    && start.matches("body/(p\\[[1-9][0-9]{0,5}\\]|tbl\\[[1-9][0-9]{0,5}\\]/tr\\[[1-9][0-9]{0,5}\\])");
            case PDF_REGION -> region!=null && part==null && start==null && end==null;
        };
        if(!valid) throw new IllegalArgumentException("Invalid native locator coordinates");
    }
    private static int line(String value) {
        if(value==null||!value.matches("[1-9][0-9]{0,8}"))return -1;
        return Integer.parseInt(value);
    }
    private static boolean cellRange(String start,String end) {
        if(start==null||end==null||!start.matches("[A-Z]{1,3}[1-9][0-9]{0,6}")||!end.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))return false;
        var a=new org.apache.poi.ss.util.CellAddress(start);
        var b=new org.apache.poi.ss.util.CellAddress(end);
        return a.getColumn()<=b.getColumn()&&a.getRow()<=b.getRow()&&b.getRow()<1048576&&b.getColumn()<16384;
    }
}
