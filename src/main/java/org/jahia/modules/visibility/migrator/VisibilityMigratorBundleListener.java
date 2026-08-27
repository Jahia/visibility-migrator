package org.jahia.modules.visibility.migrator;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes the legacy visibility modules so that jContent can take over their condition node types.
 * <p>
 * Synchronous by necessity on the target path, not by preference: the framework blocks the bundle
 * operation until every synchronous listener returns, and that is what guarantees the source modules
 * are gone, and the node types handed over, before jContent resolves and parses its own CND. An
 * asynchronous listener would race jContent's activation, which is the race this module removes.
 */
public class VisibilityMigratorBundleListener implements SynchronousBundleListener {

    private static final Logger logger = LoggerFactory.getLogger(VisibilityMigratorBundleListener.class);

    private final BundleContext context;

    public VisibilityMigratorBundleListener(BundleContext context) {
        this.context = context;
    }

    @Override
    public void bundleChanged(BundleEvent event) {
        Bundle bundle = event.getBundle();
        if (bundle == null) {
            return;
        }
        String symbolicName = bundle.getSymbolicName();
        try {
            if (MigrationSupport.TARGET.equals(symbolicName)) {
                switch (event.getType()) {
                    case BundleEvent.INSTALLED:
                        onTargetInstalled(bundle);
                        break;
                    case BundleEvent.STARTED:
                        // Covers the target becoming active without an INSTALLED this module could
                        // see - a restart of an already-installed bundle, or a node that installed
                        // its bundles in an order that had the target arrive after this one.
                        MigrationSupport.runBootstrapIfNeeded(context);
                        break;
                    default:
                        break;
                }
            } else if (MigrationSupport.SOURCES.contains(symbolicName)) {
                switch (event.getType()) {
                    case BundleEvent.INSTALLED:
                        onSourceInstalled(bundle);
                        break;
                    case BundleEvent.STOPPED:
                        onSourceStopped(bundle);
                        break;
                    default:
                        break;
                }
            }
        } catch (RuntimeException e) {
            // Never let this escape: it would abort the bundle operation the framework is running.
            logger.error("Migration step failed for bundle {}", symbolicName, e);
        }
    }

    /**
     * The main path. jContent is installed but not yet resolved or started, so it has registered no
     * condition rules and there is nothing of its to evict. Its own patch script then hands the node
     * types over when it resolves, moments later.
     */
    private void onTargetInstalled(Bundle target) {
        if (!MigrationSupport.declaresConditionTypes(target)) {
            return;
        }
        if (MigrationSupport.findLiveSources(context).isEmpty()) {
            return;
        }
        logger.info("{} v{} is being installed; removing {} first so its condition rules are not torn down",
                MigrationSupport.TARGET, target.getVersion(), MigrationSupport.SOURCES);
        MigrationSupport.uninstallSourcesLocally(context);
    }

    /**
     * A source module has come back - a Jahia upgrade re-provisioning it, typically. Left alone it
     * would register its own rules over jContent's, and removing it again later would then leave the
     * node types with no rule at all.
     * <p>
     * Removed inline, before it starts, so its rules are never registered and jContent's are never
     * displaced. Whatever installed it will then be working on an uninstalled bundle and will log a
     * failure - which is the correct outcome, since the module should not be installed at all once
     * jContent owns these node types.
     */
    private void onSourceInstalled(Bundle source) {
        Bundle target = MigrationSupport.findActiveTarget(context);
        if (target == null || !MigrationSupport.declaresConditionTypes(target)) {
            // jContent does not own these types here, so the source module is legitimate.
            return;
        }
        logger.warn("{} v{} has been installed, but {} v{} owns the condition node types; removing it again",
                source.getSymbolicName(), source.getVersion(), MigrationSupport.TARGET, target.getVersion());
        MigrationSupport.uninstallSourcesLocally(context);
    }

    /**
     * A source module was stopped - by this module, an operator, or a provisioning script. That tears
     * down its rules, and the core removes a condition by node type name, so jContent's go with them.
     * <p>
     * STOPPED is the only event needed. A bundle is always stopped before it is uninstalled or
     * updated, and stopping is what unregisters the rules - so listening to UNINSTALLED or UPDATED as
     * well would only repair a second time for the same teardown. Stopping through the administration
     * UI or {@code ModuleManager.stop} produces STOPPED and no UNINSTALLED at all, which is why
     * listening to UNINSTALLED alone was not enough.
     */
    private void onSourceStopped(Bundle source) {
        if (MigrationSupport.findActiveTarget(context) == null) {
            return;
        }
        int restored = MigrationSupport.reregisterTargetRules();
        if (restored > 0) {
            logger.info("Restored {} condition rule(s) after {} was stopped", restored, source.getSymbolicName());
        }
    }
}
