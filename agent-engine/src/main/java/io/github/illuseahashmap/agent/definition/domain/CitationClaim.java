package io.github.illuseahashmap.agent.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** A reviewed claim for a specific amount band. Evidence identities and disposition are frozen at publication,
 * never selected by the model. Missing support means no workflow decision, not the opposite decision. */
public record CitationClaim(String amountField, BigDecimal above, String decisionField, String disposition,
                            String citationsField, Set<String> requiredChunks) {
    public static CitationClaim parse(ObjectMapper json,String schema) {
        if(schema==null||schema.isBlank()) return null;
        try {
            var root=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(schema);
            var c=root.get("x-workflow-citation-claim");
            if(c==null) return null;
            var amount=field(c,"amountField"); var decision=field(c,"decisionField"); var cited=field(c,"citationsField");
            if (!"1".equals(field(c,"version")) || c.size()!=7 || !c.path("above").isNumber()
                    || !"string".equals(root.path("properties").path(decision).path("type").asText())
                    || !"array".equals(root.path("properties").path(cited).path("type").asText())
                    || !"string".equals(root.path("properties").path(cited).path("items").path("type").asText())) throw invalid();
            return new CitationClaim(amount,c.get("above").decimalValue(),decision,field(c,"disposition"),cited,
                    keys(c.path("requiredChunks")));
        } catch(java.io.IOException e){throw invalid();}
    }
    private static String field(JsonNode n,String k){
        if(!n.path(k).isTextual()||n.get(k).textValue().isBlank()) throw invalid();
        return n.get(k).textValue();
    }
    private static Set<String> keys(JsonNode list){
        if (!list.isArray()||list.isEmpty()||list.size()>32) throw invalid();
        var keys=new LinkedHashSet<String>();
        for(var key:list) if(!key.isTextual()||key.textValue().isBlank()||!keys.add(key.textValue())) throw invalid();
        return Set.copyOf(keys);
    }
    public boolean supported(JsonNode input,JsonNode answer,Set<String> observed) {
        var amount=input.path(amountField);
        if (!amount.isNumber() || amount.decimalValue().compareTo(above)<=0
                || !disposition.equals(answer.path(decisionField).textValue())) return false;
        try {
            var cited=keys(answer.path(citationsField));
            return cited.containsAll(requiredChunks) && observed.containsAll(cited);
        } catch(IllegalArgumentException invalid){return false;}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid version-bound citation claim");}
}
