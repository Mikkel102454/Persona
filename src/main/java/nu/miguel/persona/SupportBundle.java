package nu.miguel.persona;

import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.ContentFormat;
import nu.miguel.persona.content.ContentValidator;
import nu.miguel.persona.state.SqliteStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a privacy-conscious diagnostic archive without memory/blackboard values. */
public final class SupportBundle {
    private SupportBundle(){}
    public static File create(Main plugin,ContentValidator.Report validation)throws IOException{
        File folder=new File(plugin.getDataFolder(),"support");if(!folder.exists()&&!folder.mkdirs())throw new IOException("cannot create "+folder);
        File target=new File(folder,"persona-support-"+System.currentTimeMillis()+".zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(target.toPath()))){
            add(zip,"versions.txt","created="+Instant.now()+"\nplugin="+plugin.getPluginMeta().getVersion()+"\napi="+PersonaApi.API_VERSION+"\ncontent="+ContentFormat.CURRENT+"\nsqlite-schema="+SqliteStore.schemaVersion()+"\n");
            add(zip,"validation.json",validation.json());
            add(zip,"config.yml",redact(plugin.getConfig().saveToString()));
            StringBuilder runtimes=new StringBuilder();for(var runtime:plugin.behaviors().runtimeSummaries())runtimes.append("runtime npc=").append(runtime.npcDefinition()).append(" instance=").append(runtime.instance()).append(" player=").append(runtime.player().equals("shared")?"shared":"<redacted>").append(" behavior=").append(runtime.behavior()).append(" tree=").append(runtime.treeHash()).append(" paused=").append(runtime.paused()).append(" running=").append(runtime.runningPath()).append(" checkpoint=").append(runtime.checkpoint()).append(" wake=").append(runtime.wakeAt()).append(" blackboard-keys=").append(runtime.blackboardKeys()).append(" inbox=").append(runtime.inbox()).append(" dropped=").append(runtime.droppedEvents()).append('\n');for(var orphan:plugin.behaviors().orphanedRuntimes())runtimes.append("orphan scope=").append(orphan.scope()).append(" player=").append(orphan.player().isBlank()?"shared":"<redacted>").append(" npc=").append(orphan.npcDefinition()).append(" instance=").append(orphan.instance()).append(" behavior=").append(orphan.behavior()).append(" reason=").append(orphan.reason()).append('\n');
            plugin.behaviors().extensionUsage().forEach((name,usage)->runtimes.append("extension ").append(name).append(' ').append(usage).append('\n'));
            add(zip,"runtime-diagnostics.txt",runtimes.toString());
            add(zip,"extension-schemas.json",jsonSchemas(plugin.api().behaviorSchemas()));
        }
        return target;
    }
    private static void add(ZipOutputStream zip,String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static String redact(String yaml){return yaml.replaceAll("(?im)^(\\s*[^#\\n]*(?:password|secret|token|key)[^:]*:)\\s*.*$","$1 <redacted>");}
    private static String jsonSchemas(Map<String,Map<String,Object>> schemas){StringBuilder out=new StringBuilder("{");int i=0;for(var entry:schemas.entrySet()){if(i++>0)out.append(',');out.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(String.valueOf(entry.getValue()))).append('"');}return out.append('}').toString();}
    private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");}
}
