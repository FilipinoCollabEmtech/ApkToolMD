package com.fluxin.apktoolmd.support.entity;

import java.util.List;

public class FeatureEntity {
    public String type = "";
    public String name = "";
    public String icon = "grid";
    public String desc = "";
    public String url = "";
    public int id = -1;
    public int value = 0;
    public float floatValue = 0.0f;
    public List<String> options = new java.util.ArrayList<>();
    public List<FeatureEntity> children = null;
}
