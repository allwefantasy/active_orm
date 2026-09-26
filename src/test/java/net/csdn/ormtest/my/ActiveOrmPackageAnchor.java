package net.csdn.ormtest.my;

/**
 * Neighbor anchor for JDK 9+ model definition. javassist needs an already
 * loaded class in the target package to open a Lookup for defineClass.
 */
public final class ActiveOrmPackageAnchor {
    private ActiveOrmPackageAnchor() {
    }
}
