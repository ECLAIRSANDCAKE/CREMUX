package com.termux.app.ai;

import androidx.annotation.NonNull;

import org.json.JSONObject;

/** A function the model may call. {@code schema} is a JSON-Schema object describing the arguments. */
public final class AiTool {
    @NonNull public final String name;
    @NonNull public final String description;
    @NonNull public final JSONObject schema;

    public AiTool(@NonNull String name, @NonNull String description, @NonNull JSONObject schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }
}
