package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** Gemini's schema subset omits string length constraints and uses a null branch for nullable enums. */
final class GeminiSchema {
    private GeminiSchema() {}
    static JsonNode adapt(JsonNode canonical) { return convert(canonical.deepCopy()); }
    private static JsonNode convert(JsonNode node) {
        if (node.isObject()) {
            var object = (ObjectNode) node;
            if (object.has("enum") && object.get("enum").isArray()) {
                var values = (ArrayNode) object.get("enum");
                boolean nullable = false; for (var value : values) nullable |= value.isNull();
                if (nullable) {
                    var nonNull = values.arrayNode(); for (var value : values) if (!value.isNull()) nonNull.add(value);
                    var branch = object.deepCopy(); branch.put("type", "string"); branch.set("enum", nonNull);
                    object.removeAll(); object.putArray("anyOf").add(branch).addObject().put("type", "null");
                }
            }
            object.remove("maxLength");
            var names = new java.util.ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) object.set(name, convert(object.get(name)));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) ((ArrayNode) node).set(i, convert(node.get(i)));
        }
        return node;
    }
}
