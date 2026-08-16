package nu.miguel.persona.citizens;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;

public final class PersonaTrait extends Trait {
    @Persist("definition") private String definitionId;
    @Persist("instance") private String instanceId;
    public PersonaTrait(){super("persona");}
    public String definitionId(){return definitionId;}
    public String instanceId(){return instanceId;}
    public void bind(String definition,String instance){this.definitionId=definition;this.instanceId=instance;}
    public boolean bound(){return definitionId!=null&&!definitionId.isBlank();}
}
