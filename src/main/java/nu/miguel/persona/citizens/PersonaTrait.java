package nu.miguel.persona.citizens;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import java.util.UUID;

public final class PersonaTrait extends Trait {
    @Persist("definition") private String definitionId;
    @Persist("instance") private String instanceId;
    private UUID projectionViewer;
    private UUID baseNpc;
    public PersonaTrait(){super("persona");}
    public String definitionId(){return definitionId;}
    public String instanceId(){return instanceId;}
    public void bind(String definition,String instance){this.definitionId=definition;this.instanceId=instance;}
    public boolean bound(){return definitionId!=null&&!definitionId.isBlank();}
    public UUID projectionViewer(){return projectionViewer;}
    public UUID baseNpc(){return baseNpc;}
    public boolean projection(){return projectionViewer!=null;}
    public void bindProjection(String definition,String instance,UUID viewer,UUID base){bind(definition,instance);projectionViewer=viewer;baseNpc=base;}
}
