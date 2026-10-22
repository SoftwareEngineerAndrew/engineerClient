package com.engineerclient.mixinplugin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for a config whose mixins all need Odin: they target Odin's classes, or call
 * code built on Odin (its modules, settings, solvers). Without Odin none of them applies, so nothing
 * of Odin's is looked for and no mixin's body can reach a missing class. Asked before each mixin's
 * targets are looked up, so an Odin target that isn't there isn't even warned about.
 */
public class OdinMixins implements IMixinConfigPlugin {
    /** Odin is installed (mods are known before any mixin config loads). */
    static final boolean ODIN = FabricLoader.getInstance().isModLoaded("odin");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return ODIN || !needsOdin(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
    }

    /** The mixin [name] (its simple name) needs Odin. Here: all of them. */
    protected boolean needsOdin(String name) {
        return true;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
