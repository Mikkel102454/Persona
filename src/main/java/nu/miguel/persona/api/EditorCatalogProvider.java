package nu.miguel.persona.api;

import java.util.*;

/** Bounded, read-only live values exposed by an extension on the server thread. */
public interface EditorCatalogProvider {
    CatalogMetadata metadata();
    CatalogPage query(CatalogQuery query);

    enum CachePolicy { SESSION, REVISION, NONE }
    enum MissingValuePolicy { REJECT, WARN }

    record CatalogMetadata(String revision, Map<String,Object> valueSchema, String permission,
                           CachePolicy cachePolicy, Set<String> dependencyFields,
                           MissingValuePolicy missingValuePolicy) {
        public CatalogMetadata {
            if(revision==null||revision.isBlank())throw new IllegalArgumentException("catalog revision is required");
            valueSchema=EditorSchemaAnnotations.copyAndValidate(valueSchema);
            permission=permission==null?"":permission;
            cachePolicy=cachePolicy==null?CachePolicy.REVISION:cachePolicy;
            dependencyFields=dependencyFields==null?Set.of():Set.copyOf(dependencyFields);
            missingValuePolicy=missingValuePolicy==null?MissingValuePolicy.REJECT:missingValuePolicy;
        }
    }
    record CatalogQuery(String search, int page, int pageSize, Map<String,String> dependencies) {
        public CatalogQuery {
            search=search==null?"":search;
            if(page<0)throw new IllegalArgumentException("catalog page must not be negative");
            if(pageSize<1||pageSize>200)throw new IllegalArgumentException("catalog page size must be between 1 and 200");
            dependencies=dependencies==null?Map.of():Map.copyOf(dependencies);
        }
    }
    record CatalogValue(String id,String label,String description,String group,String icon,boolean deprecated) {
        public CatalogValue {
            if(id==null||id.isBlank())throw new IllegalArgumentException("catalog value id is required");
            label=label==null?id:label;description=description==null?"":description;group=group==null?"":group;icon=icon==null?"":icon;
        }
    }
    record CatalogPage(String revision,List<CatalogValue> values,int page,boolean hasMore) {
        public CatalogPage {
            if(revision==null||revision.isBlank())throw new IllegalArgumentException("catalog page revision is required");
            values=values==null?List.of():List.copyOf(values);
            if(values.size()>200)throw new IllegalArgumentException("catalog page exceeds 200 values");
            if(page<0)throw new IllegalArgumentException("catalog page must not be negative");
        }
    }
}
