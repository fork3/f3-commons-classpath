/*
 * Copyright (c) 2010-2016 fork3
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES 
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE 
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR 
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package f3.commons.classpath;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * @author n3k0nation
 *
 */
public class ClasspathScanner {
	//TODO n3k0nation: rewrite this shit
	private final static Map<URL, ClassLoader> advancedLibs = new ConcurrentHashMap<>();
	
	private ClasspathScanner() {
		throw new RuntimeException();
	}
	
	public static void addAdvancedLibrary(URL url, ClassLoader classloader) {
		advancedLibs.put(url, classloader);
	}
	
	public static void removeAdvancedLibrary(URL url) {
		advancedLibs.remove(url);
	}
	
	public static List<String> getResources(ResourceFilter filter, List<URL> urls) throws UncheckedIOException, RuntimeException {
		final ArrayList<String> list = new ArrayList<>();
		for(int i = 0; i < urls.size(); i++) {
			URL url = urls.get(i);
			String strUrl = url.toString();
			if(url.toString().endsWith("!/")) {
				strUrl = strUrl.substring(4, strUrl.length() - 2);
				try {
					url = new URL(strUrl);
				} catch (MalformedURLException e) {
					throw new RuntimeException(e);
				}
			}
			
			if(strUrl.endsWith("/")) {
				continue; //skip raw files, not in jar
			}
			
			try(JarInputStream jis = new JarInputStream(url.openStream())) {
				for(JarEntry entry = jis.getNextJarEntry(); entry != null; entry = jis.getNextJarEntry()) {
					try {
						if(entry.isDirectory()) {
							continue;
						}
						
						final String entryName = entry.getName();
						if(entryName.endsWith(".class")) {
							continue;
						}
						
						int lastSlash = entryName.lastIndexOf("/");
						final String packageName, resourceName;
						if(lastSlash == -1) {
							packageName = "";
							resourceName = entryName;
						} else {
							packageName = entryName.substring(0, lastSlash).replaceAll("/", ".");
							resourceName = entryName.substring(lastSlash + 1);
						}
						
						if(!filter.isAccept(packageName, resourceName)) {
							continue;
						}
						
						list.add(entryName);
					} finally {
						jis.closeEntry();
					}
				}
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		
		return list;
	}
	
	public static List<Class<?>> getClasses(ClassFilter filter, List<URL> urls) throws UncheckedIOException, RuntimeException {
		final ArrayList<Class<?>> list = new ArrayList<>();
		for(int i = 0; i < urls.size(); i++) {
			URL url = urls.get(i);
			String strUrl = url.toString();
			if(url.toString().endsWith("!/")) {
				strUrl = strUrl.substring(4, strUrl.length() - 2);
				try {
					url = new URL(strUrl);
				} catch (MalformedURLException e) {
					throw new RuntimeException(e);
				}
			}
			
			if(strUrl.endsWith("/")) {
				continue; //skip raw files, not in jar
			}
			
			try(JarInputStream jis = new JarInputStream(url.openStream())) {
				for(JarEntry entry = jis.getNextJarEntry(); entry != null; entry = jis.getNextJarEntry()) {
					try {
						if(entry.isDirectory()) {
							continue;
						}
						
						final String entryName = entry.getName();
						if(!entryName.endsWith(".class")) {
							continue;
						}
						
						final String canonicalName = entryName.substring(0, entryName.length() - ".class".length()).replaceAll("/", ".");
						final String packageName = canonicalName.substring(0, canonicalName.lastIndexOf("."));
						final String className = canonicalName.substring(canonicalName.lastIndexOf(".") + 1);
						if(className.contains("$") || !filter.isAccept(packageName, canonicalName)) {
							continue;
						}
						
						ClassLoader classloader = advancedLibs.get(url);
						if(classloader == null) {
							classloader = ClasspathScanner.class.getClassLoader();
						}
						
						Class<?> clazz;
						try {
							clazz = Class.forName(canonicalName, true, classloader);
						} catch(ClassNotFoundException e) {
							throw new RuntimeException("Class not found: " + canonicalName + ", jar: " + url.toExternalForm(), e);
						} catch(ExceptionInInitializerError e) {
							throw new RuntimeException("Failed to init static context for class: " + canonicalName, e);
						} catch(NoClassDefFoundError e) {
							throw new RuntimeException(e);
						}
						list.add(clazz);
					} finally {
						jis.closeEntry();
					}
				}
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		
		return list;
	}
	
	public static List<URL> getClassPathURL(String...libs) throws RuntimeException {
		final ArrayList<URL> list = new ArrayList<>();
		final String classpath = System.getProperty("java.class.path");
		final StringTokenizer st = new StringTokenizer(classpath, File.pathSeparator);
		while(st.hasMoreTokens()) {
			final String path = st.nextToken().trim();
			
			boolean adding = false;
			if(libs.length == 0) {
				adding = true;
			} else {
				for(int i = 0; i < libs.length; i++) {
					final String lib = libs[i];
					if(path.endsWith(File.separator + lib)) {
						adding = true;
						break;
					}
				}
			}
			
			if(!adding) {
				continue;
			}
			
			final File file = new File(path);
			if(!file.exists()) {
				throw new RuntimeException("Java classpath contains not exists path: " + file.toString());
			}
			
			try {
				list.add(file.toURI().toURL());
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		list.addAll(advancedLibs.keySet());
		list.trimToSize();
		return list;
	}
}
