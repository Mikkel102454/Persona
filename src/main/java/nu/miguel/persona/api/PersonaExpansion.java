package nu.miguel.persona.api;

import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** PlaceholderAPI-style expansion: instantiate it and call {@link #register()}. */
public abstract class PersonaExpansion {
    private PersonaApi api;
    public abstract String identifier();
    public abstract String author();
    public abstract String version();
    public String requiredApiVersion(){return PersonaApi.API_VERSION;}
    public boolean canRegister(){return true;}
    public Plugin owner(){return null;}
    protected abstract void registerTypes(ExpansionRegistrar registrar);
    public final boolean register(){return PersonaApi.get().register(this);}
    public final void unregister(){if(api!=null)api.unregister(this);}
    final void attach(PersonaApi value){api=Objects.requireNonNull(value);}
    final void contribute(ExpansionRegistrar registrar){registerTypes(registrar);}
}
