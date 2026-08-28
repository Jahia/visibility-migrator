# visibility-migrator

Removes the legacy `visibility` / `advanced-visibility` modules so that jContent can take ownership of
visibility condition node types. Installation is a prerequisite if visibility modules are present and 
upgrading to jContent > 3.7.1

## Why it exists

jContent 3.7.1 declares `jnt:timeOfDayCondition`, `jnt:dayOfWeekCondition` and
`jnt:startEndDateCondition` itself, and registers its own `VisibilityConditionRule` implementations
for them. The legacy `visibility` module registers rules under the *same* node type names, and the
core removes a condition by node type name alone — so uninstalling `visibility` while jContent is
live tears down **jContent's** rules. The condition map empties, `matchesConditions` then returns
`true` for everything, and conditioned content renders unconditionally with nothing logged.

This module makes sure the legacy modules are gone *before* jContent activates, so there is nothing
of jContent's to evict.

## What it does

| Trigger | Action |
|---|---|
| jContent `INSTALLED` | Uninstall the legacy modules on this node, before jContent resolves or starts |
| jContent `STARTED` | Re-check, for the case where jContent became active without an `INSTALLED` this module could see |
| Legacy module `INSTALLED` | Uninstall it again, before it starts — a Jahia upgrade re-provisioning it would otherwise displace jContent's rules |
| Legacy module `STOPPED` | Re-register jContent's rules. A bundle is always stopped before it is uninstalled or updated, and stopping is what tears its rules down — and the core removes a condition by node type name, so jContent's go with them |
| Its own activation | If jContent is already active when this module is installed, uninstall inline — the listener would never fire. jContent then needs a redeploy or refresh to hand the node types over |

It does **not** touch node types. jContent hands those over itself, from its own patch script on the
next resolve.

## Notes

The uninstall is a plain local `Bundle.stop()` / `Bundle.uninstall()`, not `ModuleManager.uninstall`.
It runs inside a bundle event, on the thread applying that bundle operation, and a clustered
operation issued from there parks on a completion latch only that same thread could release. Every
node applies the install itself and fires its own event, so a purely local uninstall converges the
cluster anyway.

The listener is a `SynchronousBundleListener` by necessity: the framework blocks the bundle operation
until it returns, which is what guarantees the legacy modules are gone before jContent starts.

## Retiring it

Safe to uninstall once the Jahia distribution stops shipping `visibility` — at that point nothing can
re-provision it and this module has nothing left to react to. Until then it is what keeps a
distribution upgrade from re-arming the problem.
