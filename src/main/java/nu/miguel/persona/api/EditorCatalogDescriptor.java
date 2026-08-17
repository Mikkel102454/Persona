package nu.miguel.persona.api;

/** Public catalog identity and immutable metadata. */
public record EditorCatalogDescriptor(String catalogId,String extensionId,String extensionVersion,
                                      EditorCatalogProvider.CatalogMetadata metadata) {}
