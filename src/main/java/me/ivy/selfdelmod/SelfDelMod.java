package me.ivy.selfdelmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import sun.misc.Unsafe;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

public class SelfDelMod implements ModInitializer {
	private Unsafe unsafe;
	private ScheduledExecutorService executorService;

	@Override
	public void onInitialize() {
		System.out.println("[SelfDelMod] Init");
        executorService = Executors.newSingleThreadScheduledExecutor();
		executorService.schedule(new SelfDeleteTask(), 10, TimeUnit.SECONDS);
    }

	class SelfDeleteTask implements Runnable
	{
		@Override
		public void run() {
			try {
				URI uri = this.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
				Path me = Paths.get(uri);
				if (me.toString().endsWith("jar")) {
					closeJarInGlobalCache(uri);
					if (closeJarInKnotClassloader(me.toString())) {
						System.out.println("[SelfDelMod] Jar closed");
						try {
							Files.deleteIfExists(me);
							System.out.println("[SelfDelMod] Jar deleted");
						} catch (IOException e) {
							System.out.println("[SelfDelMod] Failed to delete jar");
						}
					} else {
						System.out.println("[SelfDelMod] Failed to close jar");
					}
				}
			} catch (URISyntaxException e) {
			}
		}
	}

	// https://bugs.openjdk.org/browse/JDK-8239054
	private void closeJarInGlobalCache(URI fileUri) {
		try {
			URL jarUrl = URI.create("jar:"+fileUri+"!/").toURL();
			((JarURLConnection)jarUrl.openConnection()).getJarFile().close();
		} catch (IOException e) {
		}
	}

	private boolean closeJarInKnotClassloader(String jarPath) {
		if (unsafe == null) {
			try {
				Field f = Unsafe.class.getDeclaredField("theUnsafe");
				f.setAccessible(true);
				unsafe = (Unsafe) f.get(null);
			} catch (IllegalAccessException | NoSuchFieldException exc) {
				return false;
			}
		}
		// Close jar in global cache
		ClassLoader classLoader = FabricLauncherBase.getLauncher().getTargetClassLoader();
		try {
			Class<?> knotClassLoaderCls = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClassLoader");
			if (knotClassLoaderCls.isInstance(classLoader)) {
				Field urlClassLoaderField = knotClassLoaderCls.getDeclaredField("urlLoader");
				urlClassLoaderField.setAccessible(true);
				URLClassLoader urlClassLoader = (URLClassLoader) urlClassLoaderField.get(classLoader);
				return closeJarInUrlClassLoader(urlClassLoader, jarPath);
			}
		} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {}
		return false;
	}

	// https://github.com/toolfactory/jvm-driver/blob/main/java/src/main/java/io/github/toolfactory/jvm/function/catalog/SetAccessibleFunction.java
	// https://stackoverflow.com/a/78487108
	private void setAccessible(AccessibleObject accessibleObject, boolean flag) {
		unsafe.putBoolean(accessibleObject, 12, flag);  // AccessibleObject.override offset = 12
	}

	private boolean closeJarInUrlClassLoader(URLClassLoader urlClassLoader, String jarPath) {
		try {
			Field closeablesField = URLClassLoader.class.getDeclaredField("closeables");
			setAccessible(closeablesField, true);
			WeakHashMap<Closeable,Void> closeables = (WeakHashMap<Closeable,Void>)closeablesField.get(urlClassLoader);
			for (Closeable closeable : closeables.keySet()) {
				if (closeable instanceof JarFile jarFile) {
					if (jarFile.getName().equals(jarPath)) {
						try {
							jarFile.close();
						} catch (IOException ignored) {}
					}
				}
			}
			Field urlClassPathField = URLClassLoader.class.getDeclaredField("ucp");
			setAccessible(urlClassPathField, true);
			Object urlClassPath = urlClassPathField.get(urlClassLoader);
			Field loadersField = urlClassPath.getClass().getDeclaredField("loaders");
			setAccessible(loadersField, true);
			Collection<?> loaders = (Collection<?>)loadersField.get(urlClassPath);
			for (Object urlClassPathLoader : loaders.toArray()) {
				try {
					Field jarField = urlClassPathLoader.getClass().getDeclaredField("jar");
					setAccessible(jarField, true);
					JarFile jarFile = (JarFile)jarField.get(urlClassPathLoader);
					if (jarFile.getName().equals(jarPath)) {
						((Closeable)urlClassPathLoader).close();
						loaders.remove(urlClassPathLoader);  // !!! Remove invalid loader
						return true;
					}
				} catch (Throwable t) {
					// not a JAR loader so skip it
				}
			}
		} catch (NoSuchFieldException | IllegalAccessException ignored) {}
		return false;
	}
}