package org.jahia.modules.visibility.migrator;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VisibilityMigratorActivator implements BundleActivator {

    private static final Logger logger = LoggerFactory.getLogger(VisibilityMigratorActivator.class);

    private BundleListener bundleListener;

    @Override
    public void start(BundleContext context) {
        context.addBundleListener(bundleListener = new VisibilityMigratorBundleListener(context));
        logger.info("Watching for {} so it can be removed before {} takes over its condition node types",
                MigrationSupport.SOURCES, MigrationSupport.TARGET);
        MigrationSupport.runBootstrapIfNeeded(context);
    }

    @Override
    public void stop(BundleContext context) {
        if (bundleListener != null) {
            context.removeBundleListener(bundleListener);
        }
    }
}
