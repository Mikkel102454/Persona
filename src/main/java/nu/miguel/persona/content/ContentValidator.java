package nu.miguel.persona.content;

import nu.miguel.persona.api.PersonaApi;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** One validation entry point shared by commands, hosted editor sessions, and reload. */
public final class ContentValidator {
    public record Report(boolean valid,List<String> errors,Content.Registry candidate) {
        public Report { errors=List.copyOf(errors); }
        /** Stable, dependency-free machine report for commands and support tooling. */
        public String json(){StringBuilder out=new StringBuilder("{\"valid\":").append(valid).append(",\"errors\":[");for(int i=0;i<errors.size();i++){if(i>0)out.append(',');out.append('"').append(escape(errors.get(i))).append('"');}return out.append("]}").toString();}
        private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    }
    private final File root;private final Duration dialogueDelay;private final PersonaApi api;
    public ContentValidator(File root,Duration dialogueDelay,PersonaApi api){this.root=root;this.dialogueDelay=dialogueDelay;this.api=api;}
    public Report validate(){try{Content.Registry candidate=new ContentLoader(root,dialogueDelay,api).load();return new Report(true,List.of(),candidate);}catch(ContentException e){return new Report(false,e.errors(),null);}}
    /** Extension fragments consumed by the CLI and hosted editor schema exporter. */
    public Map<String,Map<String,Object>> extensionSchemas(){return api==null?Map.of():api.behaviorSchemas();}
}
