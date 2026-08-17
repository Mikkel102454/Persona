package nu.miguel.persona.api;

import java.util.Map;

/**
 * Supplies data-only editor metadata. The returned value is ordinary JSON Schema;
 * extensions never provide browser code or frontend components.
 */
@FunctionalInterface
public interface EditorSchemaProvider {
    Map<String,Object> editorSchema();
}
