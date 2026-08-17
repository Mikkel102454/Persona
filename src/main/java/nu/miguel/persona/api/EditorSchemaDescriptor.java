package nu.miguel.persona.api;

import java.util.Map;

/** Immutable editor description for one namespaced Persona content type. */
public record EditorSchemaDescriptor(String contentType, String typeId, String extensionId,
                                     String extensionVersion, Map<String,Object> schema) {
    public EditorSchemaDescriptor {
        schema = EditorSchemaAnnotations.copyAndValidate(schema);
    }
}
