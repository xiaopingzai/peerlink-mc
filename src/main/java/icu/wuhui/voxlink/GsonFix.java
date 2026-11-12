/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonPrimitive
 */
package icu.wuhui.voxlink;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class GsonFix {
    public static void addString(JsonArray jsonArray, String string) {
        jsonArray.add((JsonElement)new JsonPrimitive(string));
    }
}

