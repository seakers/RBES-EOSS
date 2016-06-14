function excel_data = get_ecv_scores_for_ref_archs()
    global ref_arch
    excel_data = zeros(5,50);
    resu = cell(5,1);
    AE = get_AE;
    params = get_params;
    for i = 1:length(ref_arch)
        resu{i} = AE.evaluateArchitecture(ref_arch{i},'Slow');
        ecv = 1;
        for p = 1:params.subobjectives.size
            panel = params.subobjectives.get(p-1);
            for o = 1:panel.size
                obj = panel.get(o-1);
                score_obj = 0;
                for s = 1:obj.size
                    subobj = obj.get(s-1);
                    score_obj = score_obj + params.subobj_weights_map.get(subobj)*resu{i}.getSubobjective_scores2.get(subobj);
                end
                excel_data(i,ecv) = score_obj;
                ecv = ecv + 1;
            end
        end
    end
end