/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package rbsa.eoss;

import java.util.ArrayList;
import java.util.Iterator;
import jess.Rete;
import java.util.TreeMap;

/**
 *
 * @author Ana-Dani
 */
import java.util.concurrent.Callable;
import jess.Fact;
import jess.RU;
import jess.Value;
import jess.ValueVector;
import org.apache.commons.lang3.StringUtils;
import rbsa.eoss.local.Params;
import java.util.HashMap;

//import rbsa.eoss.Nto1pair;

public class GenericTask implements Callable {
    private Architecture arch;
    private Resource res;
    private String type;
    private boolean debug;
    
    public GenericTask ( Architecture arch , String type)
    {
        this.arch = arch;
        this.type = type;
        //if (type.equalsIgnoreCase("Slow") || arch.getEval_mode().equalsIgnoreCase("DEBUG"))
        if (arch.getEval_mode().equalsIgnoreCase("DEBUG")) 
            debug = true;
        else
            debug = false;
    }
    
   
    public void getResource() {
        res = ArchitectureEvaluator.getInstance().getResourcePool().getResource();
    }
    
    public void freeResource () {
        ArchitectureEvaluator.getInstance().getResourcePool().freeResource( res );
        res = null;
    }
    @Override
    public Result call()
    {   System.out.println("Evaluating Arch " + arch.toBitString() + " nsats = " + arch.getNsats() + " ... " );
        
        if (!arch.isFeasibleAssignment()) {
            int diff = arch.getTotalInstruments() - Params.MAX_TOTAL_INSTR;
            System.out.println("Arch " + arch.toBitString() + " nsats = " + arch.getNsats() + " is infeasible by " + diff);
            return new Result(arch, 0.0, 1E5, null, null, null, null, null,null);
        }
    
        getResource();
        Rete r = res.getRete();
        QueryBuilder qb = res.getQueryBuilder();
        MatlabFunctions m = res.getM();
        Result resu = new Result();
        try{
            if (type.equalsIgnoreCase("Fast")) {
                resu = evaluatePerformanceFast(r, arch,qb, m);
                r.eval("(reset)");
                assertMissions(r,arch,m); 
            } else if (type.equalsIgnoreCase("Capabilities")) {
                resu = evaluateCapabilities(r, arch, qb, m);
                r.eval("(reset)");
                assertMissions(r,arch,m); 
            } else if (type.equalsIgnoreCase("Slow")) {
                resu = evaluatePerformance(r, arch, qb, m);
                r.eval("(reset)");
                assertMissions(r,arch,m); 
            } else if (type.equalsIgnoreCase("NoSynergies")) {
                resu = evaluateNoSynergies(r, arch, qb, m);
                r.eval("(reset)");
                assertMissionsNoSynergies(r,arch,m);
                
            } else {
                throw new Exception("Wrong type of task");
            }
            evaluateCost(r, arch, resu, qb, m);
            resu.setTaskType(type);
            arch.setResult(resu);
            
            System.out.println("Arch " + arch.toBitString() + " nsats = " + arch.getNsats() + " " + resu.getTaskType() + ": Science = " + resu.getScience() + "; Cost = " + resu.getCost());
        } catch (Exception e) {
            System.out.println( "EXC in Task:call: " + e.getClass() + " " + e.getMessage() + " " + e.getStackTrace() );
            freeResource();
        }       
        freeResource();
        return resu;
    }
    private Result evaluatePerformanceFast(Rete r, Architecture arch, QueryBuilder qb, MatlabFunctions m) { 
        Result result = null;
        
        try {
            r.reset();
            r.eval("(bind ?*science-multiplier* 1.0)");
            r.eval("(defadvice before (create$ >= <= < >) (foreach ?xxx $?argv (if (eq ?xxx nil) then (return FALSE))))");
            r.eval("(defadvice before (create$ sqrt + * **) (foreach ?xxx $?argv (if (eq ?xxx nil) then (bind ?xxx 0))))");
            boolean[][] mat = arch.getMat();
            //Assert measurement facts
            for (int i = 0;i<Params.norb;i++) {
                
                boolean[] payl = mat[i];
                ArrayList<String> thesubset = new ArrayList<String>();
                for (int j = 0;j<payl.length;j++) {
                    if (payl[j])  {
                        thesubset.add(Params.instrument_list[j]);
                    }
                }
                String[] subset = new String[thesubset.size()];
                subset = thesubset.toArray(subset);
                String orbit = Params.orbit_list[i];
                if (subset.length>0) {
                    String key = arch.getNsats() + " x " + orbit + "@" + m.StringArraytoStringWithSpaces(subset);
                    ArrayList<Fact> measurements = (ArrayList<Fact>) Params.capabilities.get(key);
                    for(int j = 0;j<measurements.size();j++) {
                        Fact tmp = (Fact) measurements.get(j).clone();
                        r.assertString(tmp.toString());
                    }
                }
            }
            
            //Revisit times
            Iterator parameters = Params.parameter_list.iterator();
            while (parameters.hasNext()) {
                String param = "\"" + (String)parameters.next() + "\"";
                Value v = r.eval("(update-fovs " + param + " (create$ " + m.StringArraytoStringWithSpaces(Params.orbit_list) + "))");
                if (RU.getTypeName(v.type()).equalsIgnoreCase("LIST")) {
                    ValueVector thefovs = v.listValue(r.getGlobalContext());           
                    String[] fovs = new String[thefovs.size()];
                    for (int i = 0;i<thefovs.size();i++) {
                        int tmp = thefovs.get(i).intValue(r.getGlobalContext());
                        fovs[i] = String.valueOf(tmp);
                    }
                    String key = arch.getNsats() + " x " + m.StringArraytoStringWith(fovs,"  ");
                    HashMap therevtimes = (HashMap) Params.revtimes.get(key); //key: 'Global' or 'US', value Double
                    String call = "(assert (ASSIMILATION2::UPDATE-REV-TIME (parameter " +  param + ") (avg-revisit-time-global# " + therevtimes.get("Global") + ") "
                            + "(avg-revisit-time-US# " + therevtimes.get("US") + ")))";
                    r.eval(call);
                } 
                
            }

            r.setFocus( "ASSIMILATION2" );
            r.run();
            
            //r.setFocus( "ASSIMILATION" );
            //r.run();
            
            r.setFocus( "FUZZY" );
            r.run();
            
            r.setFocus( "SYNERGIES" );
            r.run();
            
            r.setFocus( "SYNERGIES-ACROSS-ORBITS" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.setFocus( "FUZZY-REQUIREMENTS" );
            else
                r.setFocus( "REQUIREMENTS" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.setFocus( "FUZZY-AGGREGATION" );
            else
                r.setFocus( "AGGREGATION" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("CRISP-ATTRIBUTES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES"))) 
                result = aggregate_performance_score_facts(r, m, qb);
             else if ((Params.req_mode.equalsIgnoreCase("CRISP-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-CASES")))
                result = aggregate_performance_score(r);
            

            
            
        } catch (Exception e) {
            System.out.println( e.getMessage() + " " + e.getClass() + " " + e.getStackTrace());
        }
        
        return result;
    }
    
    private Result aggregate_performance_score_facts(Rete r, MatlabFunctions m, QueryBuilder qb) {
       ArrayList subobj_scores = new ArrayList();
       ArrayList obj_scores = new ArrayList();
       ArrayList panel_scores = new ArrayList();
       double science = 0.0;
       double cost = 0.0;
       FuzzyValue fuzzy_science = null;
       FuzzyValue fuzzy_cost = null;
       TreeMap<String,ArrayList<Fact>> explanations = new TreeMap<String,ArrayList<Fact>>();
       TreeMap<String,Double> tm = new TreeMap<String,Double>();
       try {
           ArrayList<Fact> vals = qb.makeQuery("AGGREGATION::VALUE");
           Fact val = vals.get(0);
           science = val.getSlotValue("satisfaction").floatValue(r.getGlobalContext());
           if (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES") || Params.req_mode.equalsIgnoreCase("FUZZY-CASES"))
               fuzzy_science = (FuzzyValue)val.getSlotValue("fuzzy-value").javaObjectValue(r.getGlobalContext());
           panel_scores = m.JessList2ArrayList(val.getSlotValue("sh-scores").listValue(r.getGlobalContext()),r);
           
           ArrayList<Fact> subobj_facts = qb.makeQuery("AGGREGATION::SUBOBJECTIVE");
           for (int n = 0;n<subobj_facts.size();n++) {
               Fact f = subobj_facts.get(n);
               String subobj = f.getSlotValue("id").stringValue(r.getGlobalContext());
               Double subobj_score = f.getSlotValue("satisfaction").floatValue(r.getGlobalContext());
               Double current_subobj_score = tm.get(subobj);
               if(current_subobj_score != null && subobj_score > current_subobj_score || current_subobj_score == null)
                   tm.put(subobj, subobj_score);
               explanations.put(subobj, qb.makeQuery("AGGREGATION::SUBOBJECTIVE (id " + subobj + ")"));   
           }
           
           for (Iterator<String> name = tm.keySet().iterator();name.hasNext();) {
               subobj_scores.add(tm.get(name.next()));
           }
       }catch(Exception e) {
                System.out.println(e.getMessage() + " " + e.getClass() + " " + e.getStackTrace());
                e.printStackTrace();
            }
       Result theresult = new Result(arch, science, cost, fuzzy_science, fuzzy_cost, subobj_scores, obj_scores, panel_scores,tm);
       if(debug) {
           theresult.setCapabilities(qb.makeQuery("REQUIREMENTS::Measurement"));
           theresult.setExplanations(explanations);
       }
       
       return theresult;
   }
        
    private Result aggregate_performance_score(Rete r) {
        ArrayList subobj_scores = new ArrayList();
        ArrayList obj_scores = new ArrayList();
        ArrayList panel_scores = new ArrayList();
        double science = 0.0;
        double cost = 0.0;
        //Subobjective scores
        for (int p = 0;p<Params.npanels;p++) {
            int nob = Params.num_objectives_per_panel.get(p);
            ArrayList subobj_scores_p = new ArrayList(nob);
            for (int o=0;o<nob;o++) {
                ArrayList subobj_p = (ArrayList)Params.subobjectives.get(p);
                ArrayList subobj_o = (ArrayList)subobj_p.get(o);
                int nsubob = subobj_o.size();
                ArrayList subobj_scores_o = new ArrayList(nsubob);
                for (int so = 0;so<nsubob;so++) {
                    String var_name = "?*subobj-" + subobj_o.get(so) + "*";
                    try {
                    subobj_scores_o.add(r.eval(var_name).floatValue(r.getGlobalContext()));
                    }catch(Exception e) {
                        System.out.println(e.getMessage());
                    }
                }
                subobj_scores_p.add(subobj_scores_o);
            }
            subobj_scores.add(subobj_scores_p);
        }
        
        //Objective scores
        for (int p = 0;p<Params.npanels;p++) {
            int nob = Params.num_objectives_per_panel.get(p);
            ArrayList obj_scores_p = new ArrayList(nob);
            for (int o=0;o<nob;o++) {
                ArrayList subobj_weights_p = (ArrayList) Params.subobj_weights.get(p);
                ArrayList subobj_weights_o = (ArrayList)subobj_weights_p.get(o);
                ArrayList subobj_scores_p = (ArrayList) subobj_scores.get(p);
                ArrayList subobj_scores_o = (ArrayList)subobj_scores_p.get(o);
                try {
                    obj_scores_p.add(Result.sumProduct(subobj_weights_o,subobj_scores_o));
                }catch(Exception e) {
                System.out.println(e.getMessage());
            }
            }
            obj_scores.add(obj_scores_p);
        }
        //Stakeholder and final score
        for (int p = 0;p<Params.npanels;p++) {
            try {
                panel_scores.add(Result.sumProduct((ArrayList)Params.obj_weights.get(p),(ArrayList)obj_scores.get(p)));
            }catch(Exception e) {
                System.out.println(e.getMessage());
            }
        }
        try{
            science = Result.sumProduct(Params.panel_weights,panel_scores);
        }catch(Exception e) {
                System.out.println(e.getMessage());
            }
        Result theresult = new Result(arch, science, cost, subobj_scores, obj_scores, panel_scores,null);
        return theresult;
    }
    
    private void evaluateCost(Rete r, Architecture arch, Result res, QueryBuilder qb, MatlabFunctions m) {
        try {
            r.eval("(focus MANIFEST)");
            r.eval("(run)");
            
            designSpacecraft(r,arch,qb,m);
            r.eval("(focus SAT-CONFIGURATION)");
            r.eval("(run)");
            
            r.eval("(focus LV-SELECTION0)");
            r.eval("(run)");
            r.eval("(focus LV-SELECTION1)");
            r.eval("(run)");
            r.eval("(focus LV-SELECTION2)");
            r.eval("(run)");
            r.eval("(focus LV-SELECTION3)");
            r.eval("(run)");
            
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.eval("(focus FUZZY-COST-ESTIMATION)");
            else
                r.eval("(focus COST-ESTIMATION)");
            r.eval("(run)");
            
            double cost = 0.0;
            FuzzyValue fzcost = new FuzzyValue("Cost", new Interval("delta",0,0),"FY04$M");
            ArrayList<Fact> missions = qb.makeQuery("MANIFEST::Mission");
            for (int i = 0;i<missions.size();i++)  {
                cost = cost + missions.get(i).getSlotValue("lifecycle-cost#").floatValue(r.getGlobalContext());
                if (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES") || Params.req_mode.equalsIgnoreCase("FUZZY-CASES"))
                    fzcost = fzcost.add((FuzzyValue)missions.get(i).getSlotValue("lifecycle-cost").javaObjectValue(r.getGlobalContext()));
            }
            
            res.setCost(cost);
            res.setFuzzy_cost(fzcost);
            if(debug)
                res.setCost_facts(missions);
        } catch(Exception e) {
            System.out.println("EXC in evaluateCost: " + e.getClass() + " " + e.getMessage() + " " + e.getStackTrace());
            e.printStackTrace();
        }
        //System.out.println("Arch " + arch.toBitString() + ": Science = " + res.getScience() + "; Cost = " + res.getCost());
    }
    
    private void designSpacecraft(Rete r, Architecture arch, QueryBuilder qb, MatlabFunctions m) {
        try {
            r.eval("(focus PRELIM-MASS-BUDGET)");
            r.eval("(run)");
            
            ArrayList<Fact> missions = qb.makeQuery("MANIFEST::Mission");
            Double[] oldmasses = new Double[missions.size()];
            for (int i = 0;i<missions.size();i++)  {
                oldmasses[i]= new Double(missions.get(i).getSlotValue("satellite-dry-mass").floatValue(r.getGlobalContext()));
            }
            Double[] diffs = new Double[missions.size()];
            double tolerance = 10*missions.size();
            boolean converged = false;
            while (!converged) {
                r.eval("(focus CLEAN1)");
                r.eval("(run)");
                
                r.eval("(focus MASS-BUDGET)");
                r.eval("(run)");
                
                r.eval("(focus CLEAN2)");
                r.eval("(run)");
                
                r.eval("(focus UPDATE-MASS-BUDGET)");
                r.eval("(run)");
                
                Double[] drymasses = new Double[missions.size()];
                double sumdiff  = 0.0; 
                double summasses  = 0.0; 
                for (int i = 0;i<missions.size();i++)  {
                    drymasses[i] = new Double(missions.get(i).getSlotValue("satellite-dry-mass").floatValue(r.getGlobalContext()));
                    diffs[i] = Math.abs(drymasses[i] - oldmasses[i]);
                    sumdiff = sumdiff + diffs[i];
                    summasses = summasses + drymasses[i];
                }
                converged = sumdiff < tolerance || summasses == 0; 
                oldmasses = drymasses;
                
            }
        } catch(Exception e) {
            System.out.println("EXC in evaluateCost: " + e.getClass() + " " + e.getMessage() + " " + e.getStackTrace());
        }
    }
    
    private Result evaluateCapabilities(Rete r, Architecture arch, QueryBuilder qb, MatlabFunctions m) {
        Result result = new Result(arch, 0.0, 0.0);
        ArrayList<Fact> capabilities = null;
        try {
            r.reset();
            assertMissions(r,arch,m);
            
            r.eval("(bind ?*science-multiplier* 1.0)");
            r.eval("(defadvice before (create$ >= <= < >) (foreach ?xxx $?argv (if (eq ?xxx nil) then (return FALSE))))");
            r.eval("(defadvice before (create$ sqrt + * **) (foreach ?xxx $?argv (if (eq ?xxx nil) then (bind ?xxx 0))))");
            
            r.setFocus( "MANIFEST" );
            r.run();
            
            r.setFocus( "CAPABILITIES" );
            r.run();
            
            r.setFocus( "FUZZY" );
            r.run();
            
            r.setFocus( "SYNERGIES" );
            r.run();
            
            capabilities = qb.makeQuery("REQUIREMENTS::Measurement");
            capabilities.addAll(qb.makeQuery("SYNERGIES::cross-registered"));
        } catch (Exception e) {
            System.out.println( e.getClass() + " " + e.getMessage() + " " + e.getStackTrace());
        }
        result.setCapabilities(capabilities);
        System.out.println("Capabilities computed for arch " + arch.toFactString());
        return result;
    }
        
    private void assertMissions(Rete r, Architecture arch, MatlabFunctions m) {
         boolean[][] mat = arch.getMat();
         try{
            for (int i= 0;i<Params.norb;i++) {
                int ninstrs = m.SumRowBool(mat,i); 
                if (ninstrs>0) {
                 String orbit = Params.orbit_list[i];
                 Orbit orb = new Orbit(orbit,1,arch.getNsats());
                 String payload = "";
                 String call = "(assert (MANIFEST::Mission (Name " + orbit + ") ";
                 for (int j= 0;j<Params.ninstr;j++) {
                     if (mat[i][j]) {
                         payload = payload + " " + Params.instrument_list[j];
                     }        
                 }
                 call = call + "(instruments " + payload + ") (lifetime 5) (launch-date 2015) (select-orbit no) " + orb.toJessSlots() + "))";
                 call = call + "(assert (SYNERGIES::cross-registered-instruments " +
                " (instruments " + payload + 
                ") (degree-of-cross-registration spacecraft) " +
                " (platform " + orbit +  " )))";
                 r.eval(call);  
                }
         }       
        } catch (Exception e) {
            System.out.println( "" + e.getClass() + " " + e.getMessage() + " " + e.getStackTrace());
        }
    }
    
    private void assertMissionsNoSynergies(Rete r, Architecture arch, MatlabFunctions m) {
         try{
             Nto1pair pair = arch.getNto1pair();
             if (pair == null) {
                 //Assert one mission per instrument
                 boolean[][] mat = arch.getMat();
                for (int o = 0;o<Params.norb;o++) {
                    
                    
                    String orbit = Params.orbit_list[o];
                    Orbit orb = new Orbit(orbit,1,arch.getNsats());
                    for (int i = 0;i<Params.ninstr;i++) {
                        if(mat[o][i]) {
                            String instr = Params.instrument_list[i];
                            String call = "(assert (MANIFEST::Mission (Name " + orbit + "-" + instr + ") ";
                            call = call + "(instruments " + instr + ")  (lifetime 5)  (launch-date 2015) (select-orbit no) " + orb.toJessSlots() + "))";
                            call = call + "(assert (SYNERGIES::cross-registered-instruments " +
                           " (instruments " + instr  + ") " +
                           " (degree-of-cross-registration spacecraft) " +
                           " (platform " + orbit +  " )))";
                            r.eval(call);  
                        }     
                    }
                } 
             } else {
                 //Assert one mission for base, one for added
                String[] base = pair.getBase();
                String add = pair.getAdded();
                String orbit = arch.getOrbit();
                Orbit orb = new Orbit(orbit,1,arch.getNsats());
                //base mission (N instruments)
                String call = "(assert (MANIFEST::Mission (Name " + orbit + "_base) ";
                call = call + "(instruments " + StringUtils.join(base, " ") + ") (lifetime 2) (launch-date 2015) (select-orbit no) " + orb.toJessSlots() + "))";
                call = call + "(assert (SYNERGIES::cross-registered-instruments " +
               " (instruments " + StringUtils.join(base, " ")  + ") " +
               " (degree-of-cross-registration spacecraft) " +
               " (platform " + orbit +  " )))";
                r.eval(call);  

                //add mission (1 instrument)
                String call2 = "(assert (MANIFEST::Mission (Name " + orbit + "_added) ";
                call2 = call2 + "(instruments " + StringUtils.join(add, " ") + ") (lifetime 2) (launch-date 2015) (select-orbit no) " + orb.toJessSlots() + "))";
                r.eval(call2);
             }
        } catch (Exception e) {
            System.out.println( "EXC in assertMissionsNoSynergies: " + e.getClass() + " " + e.getMessage() + " " + e.getStackTrace());
        }
    }
    
    private Result evaluateNoSynergies(Rete r, Architecture arch, QueryBuilder qb, MatlabFunctions m) { 
        Result result = null;
        try {
            r.reset();
            Nto1pair pair = arch.getNto1pair();
            /*if (pair == null)
                assertMissions(r,arch,m);
            else
                assertMissionsNoSynergies(r,arch,m);*/
            assertMissionsNoSynergies(r,arch,m);
            
            r.eval("(bind ?*science-multiplier* 1.0)");
            r.eval("(defadvice before (create$ >= <= < >) (foreach ?xxx $?argv (if (eq ?xxx nil) then (return FALSE))))");
            r.eval("(defadvice before (create$ sqrt + * **) (foreach ?xxx $?argv (if (eq ?xxx nil) then (bind ?xxx 0))))");
            
            r.setFocus( "MANIFEST" );
            r.run();
            
            r.setFocus( "CAPABILITIES" );
            r.run();
            
            //Revisit times
            Iterator parameters = Params.measurements_to_instruments.keySet().iterator();
            while (parameters.hasNext()) {
                String param = (String)parameters.next();
                Value v = r.eval("(update-fovs " + param + " (create$ " + m.StringArraytoStringWithSpaces(Params.orbit_list) + "))");
                if (RU.getTypeName(v.type()).equalsIgnoreCase("LIST")) {
                    ValueVector thefovs = v.listValue(r.getGlobalContext());           
                    String[] fovs = new String[thefovs.size()];
                    for (int i = 0;i<thefovs.size();i++) {
                        int tmp = thefovs.get(i).intValue(r.getGlobalContext());
                        fovs[i] = String.valueOf(tmp);
                    }
                    String key = arch.getNsats() + " x " + m.StringArraytoStringWith(fovs,"  ");
                    HashMap therevtimes = (HashMap) Params.revtimes.get(key); //key: 'Global' or 'US', value Double
                    String call = "(assert (ASSIMILATION2::UPDATE-REV-TIME (parameter " +  param + ") (avg-revisit-time-global# " + therevtimes.get("Global") + ") "
                            + "(avg-revisit-time-US# " + therevtimes.get("US") + ")))";
                    r.eval(call);
                } 
                
            }
            r.setFocus( "ASSIMILATION2" );
            r.run();
            
            r.setFocus( "ASSIMILATION" );
            r.run();

            r.setFocus( "FUZZY" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.setFocus( "FUZZY-REQUIREMENTS" );
            else
                r.setFocus( "REQUIREMENTS" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.setFocus( "FUZZY-AGGREGATION" );
            else
                r.setFocus( "AGGREGATION" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("CRISP-ATTRIBUTES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES"))) {
                result = aggregate_performance_score_facts(r, m, qb);
            } else if ((Params.req_mode.equalsIgnoreCase("CRISP-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-CASES"))){
                result = aggregate_performance_score(r);
            }
            
            
            
        } catch (Exception e) {
            System.out.println( e.getMessage() + " " + e.getClass() + " " + e.getStackTrace());
        }
        
        return result;
    }
   
    private Result evaluatePerformance(Rete r, Architecture arch, QueryBuilder qb, MatlabFunctions m) {
        Result result = new Result();
        try {
            r.reset();
            assertMissions(r,arch,m);           
            
            r.eval("(bind ?*science-multiplier* 1.0)");
            r.eval("(defadvice before (create$ >= <= < >) (foreach ?xxx $?argv (if (eq ?xxx nil) then (return FALSE))))");
            r.eval("(defadvice before (create$ sqrt + * **) (foreach ?xxx $?argv (if (eq ?xxx nil) then (bind ?xxx 0))))");
            
            r.setFocus( "MANIFEST" );
            r.run();
            //System.out.println("Manifest");
            r.setFocus( "CAPABILITIES" );
            r.run();
            //System.out.println("Capas");
            r.setFocus( "SYNERGIES" );
            r.run();
            //System.out.println("Syn");
            //Revisit times
            Iterator parameters = Params.measurements_to_instruments.keySet().iterator();
            while (parameters.hasNext()) {
                String param = (String)parameters.next();
                Value v = r.eval("(update-fovs " + param + " (create$ " + m.StringArraytoStringWithSpaces(Params.orbit_list) + "))");
                if (RU.getTypeName(v.type()).equalsIgnoreCase("LIST")) {
                    ValueVector thefovs = v.listValue(r.getGlobalContext());           
                    String[] fovs = new String[thefovs.size()];
                    for (int i = 0;i<thefovs.size();i++) {
                        int tmp = thefovs.get(i).intValue(r.getGlobalContext());
                        fovs[i] = String.valueOf(tmp);
                    }
                    String key = arch.getNsats() + " x " + m.StringArraytoStringWith(fovs,"  ");
                    HashMap therevtimes = (HashMap) Params.revtimes.get(key); //key: 'Global' or 'US', value Double
                    String call = "(assert (ASSIMILATION2::UPDATE-REV-TIME (parameter " +  param + ") (avg-revisit-time-global# " + therevtimes.get("Global") + ") "
                            + "(avg-revisit-time-US# " + therevtimes.get("US") + ")))";
                    r.eval(call);
                } 
                
            }
            //System.out.println("Revtimes");
            r.setFocus( "ASSIMILATION2" );
            r.run();
            
            r.setFocus( "ASSIMILATION" );
            r.run();
            //System.out.println("Assim");
            r.setFocus( "FUZZY" );
            r.run();
            //System.out.println("Fuzzy");
            r.setFocus( "SYNERGIES" );
            r.run();
            //System.out.println("Syn2");
            r.setFocus( "SYNERGIES-ACROSS-ORBITS" );
            r.run();
            //System.out.println("Syn3");
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.setFocus( "FUZZY-REQUIREMENTS" );
            else
                r.setFocus( "REQUIREMENTS" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("FUZZY-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES")))
                r.setFocus( "FUZZY-AGGREGATION" );
            else
                r.setFocus( "AGGREGATION" );
            r.run();
            
            if ((Params.req_mode.equalsIgnoreCase("CRISP-ATTRIBUTES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-ATTRIBUTES"))) {
                result = aggregate_performance_score_facts(r, m, qb);
            } else if ((Params.req_mode.equalsIgnoreCase("CRISP-CASES")) || (Params.req_mode.equalsIgnoreCase("FUZZY-CASES"))){
                result = aggregate_performance_score(r);
            }

            if (arch.getEval_mode().equalsIgnoreCase("DEBUG")) {
                ArrayList<Fact> partials = qb.makeQuery("REASONING::partially-satisfied");
                ArrayList<Fact> fulls = qb.makeQuery("REASONING::fully-satisfied");
                fulls.addAll(partials);
                //result.setExplanations(fulls);
            }
        } catch (Exception e) {
            System.out.println( e.getMessage() + " " + e.getClass() + " " + e.getStackTrace());
            e.printStackTrace();
        }
        return result;
    }
}
