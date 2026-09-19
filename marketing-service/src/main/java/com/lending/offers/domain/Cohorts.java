/**
 * Local fitted k-means model with train-only scaling and disjoint validation/test partitions; utility
 * preferences are explicit simulation assumptions.
 */
package com.lending.offers.domain;
import com.lending.offers.Json;

import static com.lending.offers.domain.Model.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.time.Instant;

/**
 * Local fitted k-means model with train-only scaling and disjoint validation/test partitions; utility
 * preferences are explicit simulation assumptions.
 */
public final class Cohorts {
    public static final List<String> FEATURES=List.of("logIncome","debtToIncome","utilization","logTenure","savingsToIncome","spendToIncome");
    /** Prevents instantiation of this utility-only type. */
    private Cohorts(){}
    /** Reads a numeric feature or returns its explicit missing-value fallback. */
    public static double n(JsonNode f,String key,double missing){return f.path(key).isNumber()?f.path(key).asDouble():missing;}
    /** Clamps a numeric value to the supplied inclusive interval. */
    public static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    /** Builds six bounded financial features used for local cohort assignment. */
    public static double[] vector(JsonNode f){double income=Math.max(1,n(f,"monthlyIncomeUsd",2000));return new double[]{Math.log1p(income),clamp(n(f,"monthlyDebtPaymentsUsd",income*.4)/income,0,2),clamp(n(f,"creditUtilizationPct",50)/100,0,1),Math.log1p(n(f,"bankingTenureMonths",0)),Math.log1p(clamp(n(f,"depositBalanceUsd",0)/income,0,100)),clamp(n(f,"monthlySpendUsd",income*.5)/income,0,3)};}
    /**
     * Derives normalized affordability, cost, amount and term preferences from customer financial
     * features.
     */
    public static double[] preference(JsonNode f){double income=Math.max(1,n(f,"monthlyIncomeUsd",2000)),debt=n(f,"monthlyDebtPaymentsUsd",income*.4)/income,savings=n(f,"depositBalanceUsd",0)/income;double a=.3+.2*clamp(debt,0,1),cost=.3+.1*clamp(1-savings,0,1),amount=.2,term=.2;double sum=a+cost+amount+term;return new double[]{a/sum,cost/sum,amount/sum,term/sum};}
    /**
     * Fits train-only scaling and K-means centroids, selects K on validation data and reports held-out
     * quality.
     */
    public static CohortModel train(String id,Training request,List<JsonNode> rows){
        if(rows.size()<60||rows.size()>10000)throw new Json.Fault(422,"Training needs 60-10000 completed synthetic customers");
        var sorted=new ArrayList<>(rows);sorted.sort(Comparator.comparing(x->x.path("customerId").asText()));Collections.shuffle(sorted,new Random(request.seed()));int train=(int)(rows.size()*.6),validation=(int)(rows.size()*.2);double[][] raw=sorted.stream().map(Cohorts::vector).toArray(double[][]::new);double[] means=new double[6],scales=new double[6];
        for(int i=0;i<train;i++)for(int j=0;j<6;j++)means[j]+=raw[i][j]/train;
        for(int i=0;i<train;i++)for(int j=0;j<6;j++)scales[j]+=Math.pow(raw[i][j]-means[j],2)/train;
        for(int j=0;j<6;j++)scales[j]=Math.max(.05,Math.sqrt(scales[j]));
        double[][] x=Arrays.stream(raw).map(v->scale(v,means,scales)).toArray(double[][]::new);double[][] best=null;double quality=-2;
        for(int k=2;k<=Math.min(6,train/12);k++)for(int restart=0;restart<3;restart++){
            double[][] centers=fit(x,train,k,new Random(request.seed()+k*103L+restart));int[] sizes=new int[k];for(int i=0;i<train;i++)sizes[nearest(x[i],centers)]++;if(Arrays.stream(sizes).min().orElse(0)<5)continue;
            double score=silhouette(x,train,train+validation,centers);if(score>quality){quality=score;best=centers;}
        }
        if(best==null)throw new Json.Fault(422,"Population lacks enough distinct, stable cohorts; simulate a larger batch");
        double[][] prefs=new double[best.length][4];int[] counts=new int[best.length];for(int i=0;i<train;i++){int cluster=nearest(x[i],best);counts[cluster]++;double[] p=preference(sorted.get(i));for(int j=0;j<4;j++)prefs[cluster][j]+=p[j];}for(int k=0;k<best.length;k++)for(int j=0;j<4;j++)prefs[k][j]/=counts[k];
        var metrics=new LinkedHashMap<String,Double>();metrics.put("trainingCount",(double)train);metrics.put("validationCount",(double)validation);metrics.put("testCount",(double)(rows.size()-train-validation));metrics.put("validationSilhouette",quality);metrics.put("testSilhouette",silhouette(x,train+validation,x.length,best));metrics.put("minimumTrainingCohortSize",(double)Arrays.stream(counts).min().orElseThrow());metrics.put("cohortCount",(double)best.length);metrics.put("observedAcceptanceLabels",0d);
        return new CohortModel(id,request.batchId(),request.seed(),"KMEANS-LOCAL-1",FEATURES,means,scales,best,prefs,Map.copyOf(metrics),Json.hash(sorted),Instant.now().toString());
    }
    /** Fits K-means centroids with distance-weighted initialization and bounded refinement iterations. */
    private static double[][] fit(double[][] x,int count,int k,Random random){double[][] c=new double[k][];c[0]=x[random.nextInt(count)].clone();for(int j=1;j<k;j++){double[] distances=new double[count];double sum=0;for(int i=0;i<count;i++){double min=Double.MAX_VALUE;for(int h=0;h<j;h++)min=Math.min(min,distance(x[i],c[h]));distances[i]=min;sum+=min;}double target=random.nextDouble()*sum;int chosen=count-1;for(int i=0;i<count;i++){target-=distances[i];if(target<=0){chosen=i;break;}}c[j]=x[chosen].clone();}
        for(int iteration=0;iteration<40;iteration++){double[][] next=new double[k][6];int[] sizes=new int[k];for(int i=0;i<count;i++){int j=nearest(x[i],c);sizes[j]++;for(int h=0;h<6;h++)next[j][h]+=x[i][h];}double movement=0;for(int j=0;j<k;j++){if(sizes[j]==0)next[j]=x[random.nextInt(count)].clone();else for(int h=0;h<6;h++)next[j][h]/=sizes[j];movement+=distance(c[j],next[j]);}c=next;if(movement<.00001)break;}return c;
    }
    /** Estimates cohort separation on at most 200 rows from the requested data partition. */
    private static double silhouette(double[][] x,int start,int end,double[][] centers){int count=Math.min(200,end-start);if(count<2)return 0;double sum=0;for(int i=start;i<start+count;i++){int own=nearest(x[i],centers);double[] totals=new double[centers.length];int[] sizes=new int[centers.length];for(int j=start;j<start+count;j++)if(j!=i){int c=nearest(x[j],centers);totals[c]+=Math.sqrt(distance(x[i],x[j]));sizes[c]++;}if(sizes[own]==0)continue;double a=totals[own]/sizes[own],b=Double.MAX_VALUE;for(int c=0;c<sizes.length;c++)if(c!=own&&sizes[c]>0)b=Math.min(b,totals[c]/sizes[c]);if(b!=Double.MAX_VALUE&&Math.max(a,b)>0)sum+=(b-a)/Math.max(a,b);}return sum/count;}
    /** Returns the squared Euclidean distance between two feature vectors. */
    private static double distance(double[] a,double[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=Math.pow(a[i]-b[i],2);return sum;}
    /** Standardizes features with saved training statistics and clips extreme values. */
    private static double[] scale(double[] a,double[] mean,double[] sd){double[] out=new double[a.length];for(int i=0;i<a.length;i++)out[i]=clamp((a[i]-mean[i])/sd[i],-8,8);return out;}
    /** Returns the index of the closest centroid to a feature vector. */
    public static int nearest(double[] row,double[][] centers){int winner=0;for(int k=1;k<centers.length;k++)if(distance(row,centers[k])<distance(row,centers[winner]))winner=k;return winner;}
    /** Assigns customer features to a saved cohort after applying the training transformation. */
    public static int cohort(CohortModel model,JsonNode features){return nearest(scale(vector(features),model.means(),model.scales()),model.centroids());}
    /** Blends individual financial preferences with the assigned cohort preferences at a 70/30 ratio. */
    public static double[] weights(CohortModel m,JsonNode f){double[] w=preference(f),cohort=m.preferences()[cohort(m,f)];for(int i=0;i<w.length;i++)w[i]=.7*w[i]+.3*cohort[i];return w;}
}
