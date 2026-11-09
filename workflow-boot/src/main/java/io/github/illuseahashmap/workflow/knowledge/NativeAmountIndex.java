package io.github.illuseahashmap.workflow.knowledge;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellAddress;
import static io.github.illuseahashmap.workflow.knowledge.NativeDocumentParser.Block;

/** Deterministic search sidecar for a deliberately small grammar. Never changes cited text.
 * Only table rows with an explicit unit, interval and matching native header are projected. */
final class NativeAmountIndex {
    record Bounds(BigDecimal low, boolean lowClosed, BigDecimal high, boolean highClosed) {
        String literal() {
            return (lowClosed?"[":"(")+(low==null?"":low.toPlainString())+","+
                    (high==null?"":high.toPlainString())+(highClosed?"]":")");
        }
    }
    record Entry(int row, Bounds bounds, int header, Integer qualifier,String label,NativeTableScope scope) {}
    private record Layout(int expr,int lower,int upper,String variable,int power,boolean lowerClosed,boolean upperClosed) {}
    private static final String N=MoneyLookup.NUMBER;
    private static final Pattern EXP_HEADER=Pattern.compile("(?:[\\p{IsHan}]{0,12}金额)\\s*([A-Z])\\s*[（(](?:人民币)?(万?)元(?:[，,]含税)?[）)]");
    private static final Pattern BOUND_HEADER=Pattern.compile("(?:[\\p{IsHan}]{0,12}金额)([上下])限[（(](不含|含)[，,](?:人民币)?(万?)元[）)]");
    private static final Pattern SHEET_CELL=Pattern.compile("([A-Z]{1,3}[0-9]+): (.+)");
    private static final Pattern VALUE=Pattern.compile("("+N+")(?: \\[raw=([0-9]{1,16}(?:\\.[0-9]{1,8})?)\\])?");
    private static final Pattern CONDITION=Pattern.compile("(?:必要时|视情况)([\\p{IsHan}]{4,24})(?:[。；;]|$)");
    static Map<Integer,Entry> project(NativeDocumentParser.Manifest manifest) {
        if (!Set.of("commonmark-gfm-v2","xssf-visible-rows-v2","word-body-rows-v2").contains(manifest.recipe())) return Map.of();
        var blocks=manifest.blocks();
        var exclusions=Set.copyOf(manifest.exclusions());
        var entries=new HashMap<Integer,Entry>();
        int lastEnd=0;String lastPart=null;
        for (int h=0;h<blocks.size();h++) {
            var header=blocks.get(h);
            var cells=cells(header);
            if (cells.size()<2 || !canBeHeader(header,exclusions)) continue;
            var layout=layout(cells);
            if (layout==null) continue;
            var rows=new ArrayList<Entry>();
            int i=h+1;
            for (;i<blocks.size() && sameRowSeries(header,blocks.get(i),i-h);i++) {
                var data=cells(blocks.get(i));
                if(data.size()!=cells.size()) break;
                Bounds b=bounds(data,layout);
                if(b==null) break;
                rows.add(new Entry(i,b,h,null,data.getFirst(),null));
            }
            if(rows.isEmpty())continue;
            var scope=NativeTableScope.derive(blocks,h,i,lastEnd,!header.anchor().part().equals(lastPart));
            lastEnd=i;lastPart=header.anchor().part();
            for(var row:rows) {
                Integer q=qualifier(blocks, row.row(), i, cells(blocks.get(row.row())).getFirst());
                entries.put(row.row(),new Entry(row.row(),row.bounds(),h,q,row.label(),scope));
            }
        }
        return entries;
    }
    private static boolean canBeHeader(Block b,Set<String> exclusions) {
        return switch(b.anchor().kind()) {
            case MD_LINES -> exclusions.contains("line "+(Integer.parseInt(b.anchor().start())+1)+": table delimiter");
            case WORD_BLOCKS -> b.anchor().start().endsWith("/tr[1]");
            case XLSX_CELLS -> true;
            default -> false;
        };
    }
    private static boolean sameRowSeries(Block head,Block row,int offset) {
        var h=head.anchor(); var r=row.anchor();
        if(h.kind()!=r.kind() || !h.part().equals(r.part())) return false;
        return switch(h.kind()) {
            case MD_LINES -> r.start().equals(r.end()) && Integer.parseInt(r.start())==Integer.parseInt(h.start())+1+offset;
            case WORD_BLOCKS -> r.start().equals(h.start().replace("/tr[1]","/tr["+(1+offset)+"]"));
            case XLSX_CELLS -> {
                var start=new CellAddress(h.start()); var next=new CellAddress(r.start());
                yield start.getColumn()==next.getColumn() && start.getRow()+offset==next.getRow();
            }
            default -> false;
        };
    }
    private static List<String> cells(Block b) {
        String text=b.text();
        if(b.anchor().kind()==NativeLocator.Kind.MD_LINES) {
            if(text.contains("\\|") || text.contains("\u0060")) return List.of();
            text=text.replaceFirst("^\\|","").replaceFirst("\\|$","");
        }
        var parts=Arrays.stream(text.split("\\|",-1)).map(String::strip).toList();
        if(parts.size()!=b.tableWidth()) return List.of();
        if(b.anchor().kind()!=NativeLocator.Kind.XLSX_CELLS) return parts;
        var start=new CellAddress(b.anchor().start());
        var out=new ArrayList<String>();
        for(var part:parts) {
            var m=SHEET_CELL.matcher(part);
            if(!m.matches() || !m.group(1).equals(new CellAddress(start.getRow(),start.getColumn()+out.size()).formatAsString())) return List.of();
            out.add(m.group(2));
        }
        return out;
    }
    private static Layout layout(List<String> cells) {
        int expr=-1,lo=-1,hi=-1,power=-1; String var=null; boolean lc=false,hc=false;
        for(int i=0;i<cells.size();i++) {
            var e=EXP_HEADER.matcher(cells.get(i)); var b=BOUND_HEADER.matcher(cells.get(i));
            if(e.matches()) {
                if(expr>=0 || lo>=0 || hi>=0) return null;
                expr=i;var=e.group(1);power=e.group(2).isEmpty()?0:4;
            } else if(b.matches()) {
                int unit=b.group(3).isEmpty()?0:4;
                if(expr>=0 || (power>=0 && power!=unit)) return null;
                power=unit;
                if(b.group(1).equals("下")) {if(lo>=0)return null;lo=i;lc=b.group(2).equals("含");}
                else {if(hi>=0)return null;hi=i;hc=b.group(2).equals("含");}
            }
        }
        return expr>=0 || lo>=0 && hi>=0 ? new Layout(expr,lo,hi,var,power,lc,hc):null;
    }
    private static Bounds bounds(List<String> data,Layout l) {
        BigDecimal lo=null,hi=null; boolean lc=false,hc=false;
        if(l.expr>=0) {
            String expr=data.get(l.expr).replaceAll("\\s+","").replace("<=","≤").replace(">=","≥");
            var chain=Pattern.compile("("+N+")([<≤])"+l.variable+"([<≤])("+N+")").matcher(expr);
            var one=Pattern.compile(l.variable+"([<>≤≥])("+N+")").matcher(expr);
            if(chain.matches()) {
                lo=MoneyLookup.decimal(chain.group(1));lc=chain.group(2).equals("≤");
                hi=MoneyLookup.decimal(chain.group(4));hc=chain.group(3).equals("≤");
            } else if(one.matches()) {
                String op=one.group(1);
                if(op.equals(">")||op.equals("≥")) {lo=MoneyLookup.decimal(one.group(2));lc=op.equals("≥");}
                else {hi=MoneyLookup.decimal(one.group(2));hc=op.equals("≤");}
            } else return null;
        } else {
            var lower=VALUE.matcher(data.get(l.lower));var upper=VALUE.matcher(data.get(l.upper));
            if(!lower.matches() || (!upper.matches() && !data.get(l.upper).equals("无上限"))) return null;
            lo=cellValue(lower); if(lo==null) return null;
            lc=l.lowerClosed;
            if(upper.matches()) {hi=cellValue(upper);if(hi==null)return null;hc=l.upperClosed;}
        }
        if(lo!=null && hi!=null && (lo.compareTo(hi)>0 || lo.compareTo(hi)==0 && !(lc&&hc))) return null;
        return new Bounds(lo==null?null:lo.scaleByPowerOfTen(l.power),lc,hi==null?null:hi.scaleByPowerOfTen(l.power),hc);
    }
    private static BigDecimal cellValue(java.util.regex.Matcher m) {
        var shown=MoneyLookup.decimal(m.group(1));
        return m.group(2)==null || shown.compareTo(MoneyLookup.decimal(m.group(2)))==0 ? shown:null;
    }
    /** A topical link, never proof of approval: require a unique local paragraph repeating
     * the same row label AND qualified action. No crossing intervening tables or sheets. */
    private static Integer qualifier(List<Block> blocks,int row,int end,String label) {
        var m=CONDITION.matcher(blocks.get(row).text());
        if(label.isBlank() || label.length()>32 || !m.find()) return null;
        String action=m.group(1);
        if(m.find()) return null;
        Integer match=null;
        for(int i=end;i<Math.min(end+6,blocks.size());i++) {
            var b=blocks.get(i);
            if(!b.anchor().part().equals(blocks.get(row).anchor().part()) || b.text().contains("|")
                    || b.anchor().start().contains("/tbl[")) break;
            if(b.text().contains(label) && b.text().contains(action)) {
                if(match!=null)return null;
                match=i;
            }
        }
        return match;
    }
    private NativeAmountIndex() {}
}
