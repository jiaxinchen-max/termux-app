package com.catfixture.inputbridge.core.iconmanager;

import java.io.Serializable;

public class Icon implements Serializable {
    private static final long serialVersionUID = 24362L;

    public int scaleType;
    public BitmapData bmpData;
    public String name;
    public String path;
}