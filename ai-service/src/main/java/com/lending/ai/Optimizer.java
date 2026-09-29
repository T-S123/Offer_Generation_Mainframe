package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Seeded bounded mixed-domain exploration and local refinement; produces best-tested candidates only. */
public final class Optimizer {
    private final ParameterRegistry registry;
    public Optimizer(ParameterRegistry registry){this.registry=registry;}
    public record Candidate(String id,String phase,ObjectNode configuration,Set<String> changedPaths) {}
    public record Score(String candidateId,String runId,int assessed,int eligible,Double acceptancePct,Double eligibilityPct,double expectedSelections,String phase) {}
    public record Winners(Score acceptance,Score eligibility,List<Score> frontier) {}
    public List<Candidate> explore(ObjectNode baseline,List<ParameterRegistry.Range> ranges,Set<String> allowed,Set<String> locked,int budget,long seed){
        require(budget>0&&budget<=1000&&!ranges.isEmpty()&&ranges.size()<=42,"INVALID_SEARCH","Bounded nonempty search required");
        var domains=new LinkedHashMap<String,List<JsonNode>>();
        String product=baseline.path("offer").path("product").asText();
        for(var r:ranges){
            require(allowed.contains(r.parameterId())&&!locked.contains(registry.get(r.parameterId()).stage()),"SCOPE_VIOLATION","Range is outside scope");
            require(!domains.containsKey(r.parameterId()),"DUPLICATE_PARAMETER","Duplicate range");
            domains.put(r.parameterId(),registry.levels(r,product,baseline.at(r.parameterId()),10));
        }
        require(domains.keySet().equals(allowed),"SCOPE_DOMAIN","Every allowed parameter needs one domain");
        long size=1;for(var d:domains.values())size=Math.min(1000001,size*d.size());
        var out=new LinkedHashMap<String,Candidate>();var keys=new ArrayList<>(domains.keySet());
        if(size<=budget){
            for(long i=0;i<size;i++){long index=i;var n=baseline.deepCopy();for(String k:keys){var d=domains.get(k);ParameterRegistry.set(n,k,d.get((int)(index%d.size())));index/=d.size();}add(out,n,baseline,allowed,locked,"EXPLORATION");}
        }else{
            var random=new Random(seed);
            // Stratified rotations cover each dimension without allocating the Cartesian product.
            var permutations=new ArrayList<List<Integer>>();
            for(String k:keys){var p=new ArrayList<Integer>();for(int i=0;i<domains.get(k).size();i++)p.add(i);Collections.shuffle(p,random);permutations.add(p);}
            for(int i=0;i<budget*30&&out.size()<budget;i++){
                var n=baseline.deepCopy();
                for(int j=0;j<keys.size();j++){String k=keys.get(j);var d=domains.get(k);int idx=i==0?0:i==1?d.size()-1:i<budget?permutations.get(j).get((i+j)%d.size()):random.nextInt(d.size());ParameterRegistry.set(n,k,d.get(idx));}
                add(out,n,baseline,allowed,locked,"EXPLORATION");
            }
        }
        return List.copyOf(out.values()).stream().limit(budget).toList();
    }
    public List<Candidate> refine(ObjectNode baseline,List<ParameterRegistry.Range> ranges,Set<String> allowed,Set<String> locked,List<ObjectNode> centers,Set<String> tested,int budget,long seed){
        if(budget<=0)return List.of();var out=new LinkedHashMap<String,Candidate>();var random=new Random(seed);String product=baseline.path("offer").path("product").asText();
        for(int iteration=0;iteration<budget*40&&!centers.isEmpty()&&out.size()<budget;iteration++){
            var n=centers.get(iteration%centers.size()).deepCopy();
            var r=ranges.get(random.nextInt(ranges.size()));
            var domain=registry.levels(r,product,baseline.at(r.parameterId()),10);
            if(r.low()!=null){
                var value=n.at(r.parameterId()).decimalValue();var span=r.high().subtract(r.low()).divide(java.math.BigDecimal.valueOf(20),10,java.math.RoundingMode.HALF_UP).max(r.step());
                var proposed=value.add(span.multiply(java.math.BigDecimal.valueOf(random.nextBoolean()?1:-1)));
                proposed=r.low().add(proposed.subtract(r.low()).divide(r.step(),0,java.math.RoundingMode.HALF_UP).multiply(r.step())).max(r.low()).min(r.high());
                registry.validateValue(r.parameterId(),tree(proposed),product,r.allowSentinel());ParameterRegistry.set(n,r.parameterId(),tree(proposed));
            }else ParameterRegistry.set(n,r.parameterId(),domain.get(random.nextInt(domain.size())));
            if(!tested.contains(hash(n)))add(out,n,baseline,allowed,locked,"REFINEMENT");
        }
        return List.copyOf(out.values());
    }
    private void add(Map<String,Candidate> out,ObjectNode n,ObjectNode baseline,Set<String> allowed,Set<String> locked,String phase){
        try{registry.validatePatch(baseline,n,allowed,locked);}catch(Fault e){if(Set.of("INVALID_AMOUNTS","INVALID_DATES").contains(e.code))return;throw e;}
        String id=hash(n);if(id.equals(hash(baseline)))return;out.putIfAbsent(id,new Candidate(id,phase,n,ParameterRegistry.differences(baseline,n)));
    }
    /** Applies support/floors before ranking and retains separate conditional-acceptance and eligibility winners. */
    public static Winners rank(List<Score> scores,int minimumEligible,double eligibilityFloor,double acceptanceFloor){
        var supported=scores.stream().filter(s->s.eligible()>=minimumEligible&&s.acceptancePct()!=null&&s.eligibilityPct()!=null).toList();
        var acceptance=supported.stream().filter(s->s.eligibilityPct()>=eligibilityFloor).max(Comparator.comparingDouble((Score s)->s.acceptancePct()).thenComparingDouble(s->s.eligibilityPct()).thenComparing(Score::candidateId)).orElse(null);
        var eligibility=scores.stream().filter(s->s.eligibilityPct()!=null&&(acceptanceFloor==0||s.acceptancePct()!=null&&s.acceptancePct()>=acceptanceFloor)&&s.eligible()>=minimumEligible).max(Comparator.comparingDouble((Score s)->s.eligibilityPct()).thenComparing(Score::candidateId)).orElse(null);
        var frontier=supported.stream().filter(s->supported.stream().noneMatch(t->t.acceptancePct()>=s.acceptancePct()&&t.eligibilityPct()>=s.eligibilityPct()&&(t.acceptancePct()>s.acceptancePct()||t.eligibilityPct()>s.eligibilityPct()))).toList();
        return new Winners(acceptance,eligibility,frontier);
    }
}

