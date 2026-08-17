package nu.miguel.persona.state;

import nu.miguel.persona.api.NpcMemoryService;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Versioned, human-readable memory migration files. */
public final class MemoryTransfer {
    private MemoryTransfer(){}
    public static int exportTo(PersistentNpcMemoryService memories,File file)throws IOException{
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("format",1);List<Map<String,Object>> rows=new ArrayList<>();
        for(NpcMemoryService.Entry e:memories.entries()){
            NpcMemoryService.Value v=e.value();Map<String,Object> row=new LinkedHashMap<>();row.put("scope",e.scope().name().toLowerCase(Locale.ROOT));
            if(e.player()!=null)row.put("player",e.player().toString());row.put("npc",e.npcDefinition());row.put("instance",e.instance());row.put("key",e.key());row.put("type",v.type().name());
            row.put("value",v.type()==NpcMemoryService.Type.TIMESTAMP?v.timestampValue().toString():v.value());row.put("created-at",v.createdAt().toString());row.put("updated-at",v.updatedAt().toString());
            if(v.expiresAt()!=null)row.put("expires-at",v.expiresAt().toString());row.put("source",v.source());rows.add(row);
        }
        yaml.set("memories",rows);File parent=file.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("cannot create "+parent);yaml.save(file);return rows.size();
    }
    public static int importFrom(PersistentNpcMemoryService memories,File file)throws Exception{
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);if(yaml.getInt("format")!=1)throw new IllegalArgumentException("unsupported memory export format");int count=0;
        for(Map<?,?> raw:yaml.getMapList("memories")){
            String scope=text(raw,"scope"),player=text(raw,"player"),npc=text(raw,"npc"),instance=text(raw,"instance"),key=text(raw,"key"),type=text(raw,"type"),source=text(raw,"source");
            UUID playerId=scope.equalsIgnoreCase("global")?null:UUID.fromString(player);NpcMemoryService.Type valueType=NpcMemoryService.Type.valueOf(type.toUpperCase(Locale.ROOT));Object value=raw.get("value");
            if(valueType==NpcMemoryService.Type.TIMESTAMP)value=Instant.parse(String.valueOf(value));
            NpcMemoryService.Value restored=new NpcMemoryService.Value(valueType,value,Instant.parse(text(raw,"created-at")),Instant.parse(text(raw,"updated-at")),raw.get("expires-at")==null?null:Instant.parse(String.valueOf(raw.get("expires-at"))),source);
            if(!restored.expired(Instant.now())){memories.restore(new NpcMemoryService.Entry(playerId,npc,instance,key,restored),"import:"+file.getName());count++;}
        }
        memories.flush();return count;
    }
    private static String text(Map<?,?> row,String key){Object value=row.get(key);return value==null?"":String.valueOf(value);}
}
