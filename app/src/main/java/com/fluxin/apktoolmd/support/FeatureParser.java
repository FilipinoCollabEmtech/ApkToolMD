package com.fluxin.apktoolmd.support;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FeatureParser {

    public static List<FeatureEntity> parse(String json) {
        List<FeatureEntity> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                FeatureEntity entity = new FeatureEntity();
                entity.type = obj.optString("type", "");
                entity.name = obj.optString("name", "");
                entity.desc = obj.optString("desc", "");
                entity.id = obj.optInt("id", -1);
                entity.value = obj.optInt("value", 0);
                entity.floatValue = (float) obj.optDouble("value", 0.0);
                entity.url = obj.optString("url", "");
                if (obj.has("options")) {
                    JSONArray opts = obj.getJSONArray("options");
                    for (int j = 0; j < opts.length(); j++) {
                        entity.options.add(opts.getString(j));
                    }
                }
                if (obj.has("children")) {
                    entity.children = parse(obj.getJSONArray("children").toString());
                }
                list.add(entity);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
}
