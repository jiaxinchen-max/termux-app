package com.catfixture.inputbridge.core.iconmanager;

import java.io.Serializable;
import java.util.List;

public class IconPack implements Serializable {
    private static final long serialVersionUID = 23442L;

    public boolean isEnabled;
    public long packSize;
    public String author;
    public Icon customIcon;
    public List<Icon> icons;
    public String name;
}