package io.github.illuseahashmap.workflow.knowledge;

import java.util.*;
import java.util.regex.Pattern;
import static io.github.illuseahashmap.workflow.knowledge.NativeDocumentParser.Block;

/** Search hints, not grants or policy verdicts. Scope is derived from a native caption, never a monetary hit. */
record NativeTableScope(List<String> keys, int title, Integer basis, Integer note) {
    private static final Pattern WORD=Pattern.compile("[\\p{IsHan}]+|[a-z]{2,32}");
    private static final Pattern UNCONFIRMED=Pattern.compile("未知|未登记|不明|未确定|是否|不是|不属于|而非|非该|未确认|待核");
    private static final Pattern NEGATED=Pattern.compile("(?:不是|不属于|而非|并非)[^，,。；;？?\\n]*?(?=而是|[，,。；;？?\\n]|$)");
    private static final Pattern GENERIC=Pattern.compile(
            "第[零一二三四五六七八九十0-9]+条|审批|金额|矩阵|规则|细则|制度|授权|含税|人民币|表格|标准|管理|工单|定额|预算|费用|口径");
    static boolean assertsCategory(String query){
        return !UNCONFIRMED.matcher(query).find();
    }
    static List<String> queryKeys(String query){
        return keys(NEGATED.matcher(query).replaceAll(" "));
    }
    static List<String> keys(String text){
        var out=new LinkedHashSet<String>();
        var m=WORD.matcher(GENERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" "));
        while(m.find() && out.size()<256){
            String w=m.group();
            for(int n=Math.min(8,w.length());n>=2 && out.size()<256;n--)
                for(int i=0;i+n<=w.length() && out.size()<256;i++) out.add(w.substring(i,i+n));
        }
        return List.copyOf(out);
    }
    static String literal(List<String> keys){return "{"+String.join(",",keys)+"}";}
    private static String plain(Block b){return b.text().replaceFirst("^(?:#+\\s*|[A-Z]{1,3}[0-9]+: )","").strip();}
    private static boolean caption(Block b){
        String s=plain(b);
        return b.tableWidth()<=1 && s.length()>=2 && s.length()<=48 && !s.matches(".*[。；：，|].*");
    }
    static NativeTableScope derive(List<Block> blocks,int header,int end,int floor,boolean first){
        Block h=blocks.get(header);
        int title=-1; Integer basis=null,note=null;
        for(int i=header-1;i>=floor && h.anchor().part().equals(blocks.get(i).anchor().part());i--){
            var b=blocks.get(i);
            if(b.tableWidth()>1)break;
            if(caption(b) && !keys(plain(b)).isEmpty()){title=i;break;}
        }
        String name=title<0?"":plain(blocks.get(title));
        if(h.anchor().kind()==NativeLocator.Kind.XLSX_CELLS && !keys(h.anchor().part()).isEmpty()
                && (name.isEmpty() || first && !caption(blocks.get(header-1)))) {
            // A specific sheet caption overrides a generic document preamble; its locator is the header.
            name=h.anchor().part();title=header;
        }
        if(title<0 || keys(name).isEmpty()) return null;
        if(first) for(int i=header-1;i>=floor && h.anchor().part().equals(blocks.get(i).anchor().part());i--){
            var b=blocks.get(i);
            if(!caption(b) && b.tableWidth()<=1 && b.text().matches(".*(?:按|以|计入|适用).*")){
                basis=i;break;
            }
        }
        if(end<blocks.size()){
            var b=blocks.get(end);
            if(h.anchor().part().equals(b.anchor().part()) && b.tableWidth()<=1 && !caption(b)
                    && b.text().matches(".*(?:补录|补充|选行|退回|不得默认|仅适用).*")) note=end;
        }
        return new NativeTableScope(keys(name),title,basis,note);
    }
}
