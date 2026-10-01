# Hotswap

Push engineerClient code changes into a running game, no restart.

**Setup (once):** a Prism instance running JetBrains Runtime 25 with

    -XX:+UseG1GC -XX:+AllowEnhancedClassRedefinition
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005

Enhanced redefinition needs G1 or Serial (not ZGC). Bind JDWP to 127.0.0.1 only: it's full
control of the JVM. On the NixOS box this is the "f7 hotswap" instance, sharing f7's game folder.

**Use:** `tools/hotswap/hotswap.sh` builds, deploys the jar (atomic, as deploy.sh), then sends
the classes that changed since the last send to this game process. Classes not loaded yet load
from the new jar.

**Works:** method bodies, new or removed methods and lambdas, new fields (but they start at
0/null: initializers don't run; the tool lists them).

**Needs a restart:** mixins (applied at class load), new modules or settings (Odin registers
them once), anything run in an `init` that has already run, changed superclasses.
