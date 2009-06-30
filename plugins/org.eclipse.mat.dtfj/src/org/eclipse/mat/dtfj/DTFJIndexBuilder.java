/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.mat.dtfj;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.lang.reflect.Modifier;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.ArrayLong;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.collect.IteratorInt;
import org.eclipse.mat.collect.IteratorLong;
import org.eclipse.mat.parser.IIndexBuilder;
import org.eclipse.mat.parser.IPreliminaryIndex;
import org.eclipse.mat.parser.index.IndexManager;
import org.eclipse.mat.parser.index.IndexWriter;
import org.eclipse.mat.parser.index.IIndexReader.IOne2ManyIndex;
import org.eclipse.mat.parser.index.IIndexReader.IOne2OneIndex;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.IProgressListener.Severity;

import com.ibm.dtfj.image.CorruptData;
import com.ibm.dtfj.image.CorruptDataException;
import com.ibm.dtfj.image.DataUnavailable;
import com.ibm.dtfj.image.Image;
import com.ibm.dtfj.image.ImageAddressSpace;
import com.ibm.dtfj.image.ImageFactory;
import com.ibm.dtfj.image.ImagePointer;
import com.ibm.dtfj.image.ImageProcess;
import com.ibm.dtfj.image.ImageSection;
import com.ibm.dtfj.image.ImageStackFrame;
import com.ibm.dtfj.image.ImageThread;
import com.ibm.dtfj.image.MemoryAccessException;
import com.ibm.dtfj.java.JavaClass;
import com.ibm.dtfj.java.JavaClassLoader;
import com.ibm.dtfj.java.JavaField;
import com.ibm.dtfj.java.JavaHeap;
import com.ibm.dtfj.java.JavaLocation;
import com.ibm.dtfj.java.JavaMethod;
import com.ibm.dtfj.java.JavaMonitor;
import com.ibm.dtfj.java.JavaObject;
import com.ibm.dtfj.java.JavaReference;
import com.ibm.dtfj.java.JavaRuntime;
import com.ibm.dtfj.java.JavaStackFrame;
import com.ibm.dtfj.java.JavaThread;
import com.ibm.dtfj.runtime.ManagedRuntime;

/**
 * @author ajohnson
 */
public class DTFJIndexBuilder implements IIndexBuilder
{
    /** How many elements in an object array to examine at once */
    private static final int ARRAY_PIECE_SIZE = 100000;
    /** How many bytes to scan in a native stack frame when looking for GC roots */
    private static final int NATIVE_STACK_FRAME_SIZE = 2048;
    /** How many bytes to scan in a Java stack frame when looking for GC roots */
    private static final int JAVA_STACK_FRAME_SIZE = 256;
    /**
     * How many bytes to scan in a huge Java stack section when looking for GC
     * roots
     */
    private static final long JAVA_STACK_SECTION_MAX_SIZE = 1024 * 1024L;
    /**
     * How many bytes to scan in a huge native stack section when looking for GC
     * roots
     */
    private static final long NATIVE_STACK_SECTION_MAX_SIZE = 1024 * 1024L;
    /** print out extra information to the console */
    private static boolean verbose = false;
    /** Whether DTFJ has root support */
    private static final boolean haveDTFJRoots = true;
    /** Whether to use DTFJ Root support */
    private static final boolean useDTFJRoots = true;
    /**
     * Whether DTFJ has references support - per instance as can switch on for
     * dumps without field info
     */
    private boolean haveDTFJRefs = false;
    /**
     * Whether to use DTFJ references support - per instance as can switch on
     * for dumps without field info
     */
    private boolean useDTFJRefs = false;
    /**
     * Whether to include thread roots (e.g. Java_Locals) as full roots, or just
     * thread roots + refs from the thread
     */
    private static final boolean useThreadRefsNotRoots = true;
    /**
     * Find roots - mark all unreferenced objects as roots so that not many
     * objects are lost by the initial GC by MAT
     */
    private static final boolean presumeRoots = false;
    /** Whether to mark all class loaders (if presume roots is true) */
    private static final boolean markAllLoaders = false;
    /** Whether to mark system classes as heap roots */
    private static final boolean useSystemClassRoots = true;
    /** Whether to skip heap roots marked marked as weak/soft reference etc. */
    private static final boolean skipWeakRoots = true;
    /**
     * Whether to guess finalizable objects as those unreferenced objects with
     * finalizers
     */
    private static final boolean guessFinalizables = true;
    /** whether to print out debug information and make errors more severe */
    private static final boolean debugInfo = false;
    /** severity flag for internal - info means error worked around safely */
    private static final IProgressListener.Severity Severity_INFO = debugInfo ? Severity.ERROR : Severity.INFO;
    /** severity flag for internal - warning means possible problem */
    private static final IProgressListener.Severity Severity_WARNING = debugInfo ? Severity.ERROR : Severity.WARNING;
    /** How many times to print out a repeating error */
    private static int errorCount = debugInfo ? 200 : 20;

    /** The actual dump file */
    private File dump;
    /** The string prefix used to build the index files */
    private String pfx;
    /** The DTFJ Image */
    private Image image;
    /** The DTFJ version of the Java runtime */
    private JavaRuntime run;
    /** Used to cache DTFJ images */
    private static HashMap<File, SoftReference<Image>> imageMap = new HashMap<File, SoftReference<Image>>();

    /** The outbound references index */
    private IndexWriter.IntArray1NWriter outRefs;
    /** The array size in bytes index */
    private IOne2OneIndex arrayToSize;
    /** The object/class id number to address index */
    private IndexWriter.Identifier indexToAddress;
    /** The object id to class id index */
    private IndexWriter.IntIndexCollector objectToClass;
    /** The class id to MAT class information index */
    private HashMapIntObject<ClassImpl> idToClass;
    /** The map of object ids to lists of associated GC roots for that object id */
    private HashMapIntObject<List<XGCRootInfo>> gcRoot;
    /** The map of thread object ids to GC roots owned by that thread */
    private HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> threadRoots;

    /** Flag used to not guess if GC roots finds finalizables */
    private boolean foundFinalizableGCRoots = false;

    /** Used to remember dummy addresses for classes without addresses */
    private HashMap<JavaClass, Long> dummyClassAddress = new HashMap<JavaClass, Long>();
    /** Used to remember dummy addresses for methods without addresses */
    private HashMap<JavaMethod, Long> dummyMethodAddress = new HashMap<JavaMethod, Long>();
    /**
     * Used to remember method addresses in case two different methods attempt
     * to use the same address
     */
    private HashMap<Long, JavaMethod> methodAddresses = new HashMap<Long, JavaMethod>();
    /** The next address to use for a class without an address */
    private long nextClassAddress = 0x1000000080000000L;

    /** Used to store the addresses of all the classes loaded by each class loader */
    HashMapIntObject<ArrayLong> loaderClassCache;
    /**
     * Just used to check efficiency of pseudo roots - holds alternative set of
     * roots.
     */
    private HashMap<Integer, String> missedRoots;
    /**
     * Used to see how much memory has been freed by the initial garbage
     * collection
     */
    private HashMapIntObject<ClassImpl> idToClass2;
    /**
     * Used to see how much memory has been freed by the initial garbage
     * collection
     */
    private IndexWriter.IntIndexCollector objectToClass2;

    /**
     * Use to see how much memory has been freed by the initial garbage
     * collection
     */
    static class ObjectToSize
    {
        /** For small objects */
        private byte objectToSize[];
        /** For large objects */
        private HashMap<Integer, Integer> bigObjs = new HashMap<Integer, Integer>();
        private static final int SHIFT = 3;
        private static final int MASK = 0xff;

        ObjectToSize(int size)
        {
            objectToSize = new byte[size];
        }

        int get(int index)
        {
            Integer size = bigObjs.get(index);
            if (size != null)
                return size;
            return (objectToSize[index] & MASK) << SHIFT;
        }

        /**
         * Rely on most object sizes being small, and having the lower 3 bits
         * zero. Some classes will have sizes with lower 3 bits not zero, but
         * there are a limited number of these. It is better to use expand the
         * range for ordinary objects.
         * 
         * @param index
         * @param size
         */
        void set(int index, int size)
        {
            if ((size & ~(MASK << SHIFT)) == 0)
            {
                objectToSize[index] = (byte) (size >>> SHIFT);
            }
            else
            {
                bigObjs.put(index, size);
            }
        }
    }

    private ObjectToSize objectToSize;

    /* Message counts to reduce duplicated messages */
    private int msgNgetRefsMissing = errorCount;
    private int msgNgetRefsExtra = errorCount;
    private int msgNarrayRefsNPE = errorCount;
    private int msgNgetRefsUnavailable = errorCount;
    private int msgNgetRefsCorrupt = errorCount;
    private int msgNbigSegs = errorCount;
    private int msgNinvalidArray = errorCount;
    private int msgNinvalidObj = errorCount;
    private int msgNbrokenEquals = errorCount;
    private int msgNbrokenInterfaceSuper = errorCount;
    private int msgNmissingLoaderMsg = errorCount;
    private int msgNcorruptCount = errorCount;
    private int msgNrootsWarning = errorCount;
    private int msgNguessFinalizable = errorCount;
    private int msgNgetRefsAllMissing = errorCount;
    private int msgNgetSuperclass = errorCount;
    private int msgNnullThreadObject = errorCount;
    private int msgNbadThreadInfo = errorCount;
    private int msgNunexpectedModifiers = errorCount;
    private int msgNcorruptSection = errorCount;
    private int msgNclassForObject = errorCount;
    private int msgNtypeForClassObject = errorCount;
    private int msgNobjectSize = errorCount;
    private int msgNoutboundReferences = errorCount;

    /*
     * (non-Javadoc)
     * @see org.eclipse.mat.parser.IIndexBuilder#cancel() Appears to be called
     * first before anything happens
     */
    public void cancel()
    {
        // Close DTFJ Image if possible
        image = null;

        if (outRefs != null)
        {
            outRefs.cancel();
            outRefs = null;
        }
        if (arrayToSize != null)
        {
            try
            {
                arrayToSize.close();
                arrayToSize.delete();
            }
            catch (IOException e)
            {}
            arrayToSize = null;
        }
        indexToAddress = null;
        objectToClass = null;
        idToClass = null;
        if (objectToClass2 != null)
        {
            try
            {
                objectToClass2.close();
                objectToClass2.delete();
            }
            catch (IOException e)
            {}
            objectToClass2 = null;
        }
        idToClass2 = null;
        objectToSize = null;
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.mat.parser.IIndexBuilder#clean(int[],
     * org.eclipse.mat.util.IProgressListener) Called after initial garbage
     * collection to show new indices for objects and -1 for object which are
     * garbage and have been deleted.
     */
    public void clean(int[] purgedMapping, IProgressListener listener) throws IOException
    {
        if (purgedMapping == null)
        {
            listener.sendUserMessage(Severity.ERROR, Messages.DTFJIndexBuilder_NullPurgedMapping, null);
            return;
        }
        listener.beginTask(Messages.DTFJIndexBuilder_PurgingDeadObjectsFromImage, purgedMapping.length / 10000);
        int count = 0;
        long memFree = 0;
        for (int i = 0; i < purgedMapping.length; ++i)
        {
            if (i % 10000 == 0)
            {
                listener.worked(1);
                if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
            }
            if (purgedMapping[i] == -1)
            {
                ++count;
                if (objectToSize != null)
                {
                    int objSize = objectToSize.get(i);
                    memFree += objSize;
                    if (verbose)
                    {
                        int type = objectToClass2.get(i);
                        debugPrint("Purging " + i + " size " + objSize + " type " + type + " " + idToClass2.get(type)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    }
                }
                if (missedRoots != null && missedRoots.containsKey(i))
                {
                    debugPrint("Alternative roots would have found root " + i + " " + missedRoots.get(i)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                // debugPrint("Remap "+i+"->"+purgedMapping[i]);
            }
        }
        listener.sendUserMessage(Severity.INFO, MessageFormat.format(Messages.DTFJIndexBuilder_PurgedIdentifiers,
                        count, memFree), null);
        // Free memory
        objectToSize = null;
        missedRoots = null;
        // Debug
        listener.done();
    }

    /*
     * (non-Javadoc)
     * @seeorg.eclipse.mat.parser.IIndexBuilder#fill(org.eclipse.mat.parser.
     * IPreliminaryIndex, org.eclipse.mat.util.IProgressListener) Fill the
     * initial index with: mapping object index to address
     */
    public void fill(IPreliminaryIndex index, IProgressListener listener) throws SnapshotException, IOException
    {

        long then1 = System.currentTimeMillis();

        // This is 100% on the progress bar
        final int workCount = 100000;
        // How many objects to process before indicating to the progress bar
        final int workObjectsStep = 10000;
        listener.beginTask(MessageFormat.format(Messages.DTFJIndexBuilder_ProcessingImageFromFile, dump), workCount);
        int workCountSoFar = 0;

        XSnapshotInfo ifo = index.getSnapshotInfo();

        // The dump may have changed, so reread it
        clearCachedDump(dump);
        Serializable dumpType = ifo.getProperty("$heapFormat"); //$NON-NLS-1$
        image = getDump(dump, dumpType);

        long now1 = System.currentTimeMillis();
        listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                        Messages.DTFJIndexBuilder_TookmsToGetImageFromFile, (now1 - then1), dump, dumpType), null);

        // Basic information
        try
        {
            ifo.setCreationDate(new Date(image.getCreationTime()));
        }
        catch (DataUnavailable e)
        {
            listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_NoDateInImage, e);
        }

        // Find the JVM
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingJVM);
        run = getRuntime(image, listener);
        int pointerSize = getPointerSize(run, listener);
        ifo.setIdentifierSize(pointerSize);

        try
        {
            ifo.setJvmInfo(run.getVersion());
            listener.sendUserMessage(Severity.INFO, MessageFormat.format(Messages.DTFJIndexBuilder_JVMVersion, ifo
                            .getJvmInfo()), null);
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_NoRuntimeVersionFound, e);
            try
            {
                ifo.setJvmInfo(run.getFullVersion());
                listener.sendUserMessage(Severity.INFO, MessageFormat.format(Messages.DTFJIndexBuilder_JVMFullVersion,
                                ifo.getJvmInfo()), null);
            }
            catch (CorruptDataException e2)
            {
                listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_NoRuntimeFullVersionFound, e2);
            }
        }

        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_Pass1);

        indexToAddress = new IndexWriter.Identifier();

        // Pass 1

        // Find last address of heap - use for dummy class addresses
        long lastAddress = 0x0;
        for (Iterator i = run.getHeaps(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingHeaps, run))
                continue;
            JavaHeap jh = (JavaHeap) next;
            for (Iterator i2 = jh.getSections(); i2.hasNext();)
            {
                Object next2 = i2.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingHeapSections, run))
                    continue;
                ImageSection is = (ImageSection) next2;
                long endAddr = is.getBaseAddress().add(is.getSize()).getAddress();
                lastAddress = Math.max(lastAddress, endAddr);
            }
        }
        if (lastAddress != 0)
            nextClassAddress = (lastAddress + 7L) & ~7L;

        // Find the bootstrap loader using the idea that it it the only loader
        // to have loaded itself
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingClassLoaders);
        JavaObject bootLoaderObject = null;
        JavaClassLoader bootLoader = null;
        long bootLoaderAddress = 0, fixedBootLoaderAddress = 0;
        ClassImpl bootLoaderType = null;
        HashMap<JavaObject, JavaClassLoader> loaders = new HashMap<JavaObject, JavaClassLoader>();
        HashSet<JavaClass> loaderTypes = new HashSet<JavaClass>();
        for (Iterator i = run.getJavaClassLoaders(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingClassLoaders1, run))
                continue;
            JavaClassLoader jcl = (JavaClassLoader) next;
            long loaderAddress = 0;
            try
            {
                JavaObject loaderObject = jcl.getObject();
                // Remember the class loader
                loaders.put(loaderObject, jcl);
                if (loaderObject == null)
                {
                    // Potential boot loader
                    debugPrint("Found class loader with null Java object " + jcl); //$NON-NLS-1$
                    bootLoader = jcl;
                    bootLoaderObject = loaderObject;
                    bootLoaderAddress = 0;
                    fixedBootLoaderAddress = fixBootLoaderAddress(bootLoaderAddress, bootLoaderAddress);
                    // Boot loader with 0 address needs a dummy entry as no Java
                    // object for it will be found
                    indexToAddress.add(fixedBootLoaderAddress);
                    debugPrint("adding boot object at " + format(bootLoaderAddress)); //$NON-NLS-1$
                }
                else
                {
                    // Get address first, in case getting class fails
                    loaderAddress = loaderObject.getID().getAddress();
                    if (bootLoader == null)
                    {
                        // Make sure there is some kind of boot loader, this
                        // should be replaced later.
                        bootLoader = jcl;
                        bootLoaderObject = loaderObject;
                        bootLoaderAddress = loaderAddress;
                        fixedBootLoaderAddress = fixBootLoaderAddress(bootLoaderAddress, bootLoaderAddress);
                    }
                    JavaClass loaderObjectClass = loaderObject.getJavaClass();
                    loaderTypes.add(loaderObjectClass);
                    String loaderClassName = getClassName(loaderObjectClass, listener);
                    if (loaderClassName.equals("*System*")) //$NON-NLS-1$
                    {
                        // Potential boot loader - Javacore
                        debugPrint("Found class loader of type *System* " + jcl); //$NON-NLS-1$
                        bootLoader = jcl;
                        bootLoaderObject = loaderObject;
                        bootLoaderAddress = loaderAddress;
                        fixedBootLoaderAddress = fixBootLoaderAddress(bootLoaderAddress, bootLoaderAddress);
                        // No need for dummy Java object
                        debugPrint("adding boot object at " + format(bootLoaderAddress)); //$NON-NLS-1$
                    }
                    else
                    {
                        debugPrint("Found class loader " + loaderClassName + " at " + format(loaderAddress)); //$NON-NLS-1$ //$NON-NLS-2$

                        JavaClassLoader jcl2 = getClassLoader(loaderObjectClass, listener);
                        if (jcl.equals(jcl2))
                        {
                            debugPrint("Found boot class loader " + loaderClassName + " at " + format(loaderAddress)); //$NON-NLS-1$ //$NON-NLS-2$
                            bootLoader = jcl;
                            bootLoaderObject = loaderObject;
                            bootLoaderAddress = loaderAddress;
                            fixedBootLoaderAddress = fixBootLoaderAddress(bootLoaderAddress, bootLoaderAddress);
                        }
                        else
                        {
                            debugPrint("Found other class loader " + loaderClassName + " at " + format(loaderAddress)); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
            catch (CorruptDataException e)
            {
                // 1.4.2 AIX 64-bit CorruptDataExceptions
                // from Class loader objects: 32/64-bit problem
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemFindingClassLoaderInformation, format(loaderAddress)),
                                e);
            }
        }
        if (bootLoader == null)
        {
            // Very corrupt dump with no useful loader information
            fixedBootLoaderAddress = fixBootLoaderAddress(bootLoaderAddress, bootLoaderAddress);
            // Boot loader with 0 address needs a dummy entry as no Java object
            // for it will be found
            indexToAddress.add(fixedBootLoaderAddress);
            debugPrint("No boot class loader found so adding dummy boot class loader object at " //$NON-NLS-1$
                            + format(fixedBootLoaderAddress));
        }

        // Holds all of the classes as DTFJ JavaClass - just used in this
        // method.
        // Make a tree set so that going over all the classes is
        // predictable and cache friendly.
        final IProgressListener listen = listener;
        TreeSet<JavaClass> allClasses = new TreeSet<JavaClass>(new Comparator<JavaClass>()
        {
            public int compare(JavaClass o1, JavaClass o2)
            {
                long clsaddr1 = getClassAddress(o1, listen);
                long clsaddr2 = getClassAddress(o2, listen);
                return clsaddr1 < clsaddr2 ? -1 : clsaddr1 > clsaddr2 ? 1 : 0;
            }
        });

        // Find all the classes (via the class loaders), and remember them
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingClasses);
        for (Iterator i = run.getJavaClassLoaders(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingClassLoaders, run))
                continue;
            JavaClassLoader jcl = (JavaClassLoader) next;
            for (Iterator j = jcl.getDefinedClasses(); j.hasNext();)
            {
                Object next2 = j.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingClasses, jcl))
                    continue;
                JavaClass j2 = (JavaClass) next2;
                allClasses.add(j2);
            }
        }

        // Find all the objects - don't store them as too many
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingObjects);
        int objProgress = 0;
        final int s2 = indexToAddress.size();
        for (Iterator i = run.getHeaps(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingHeaps, run))
                continue;
            JavaHeap jh = (JavaHeap) next;
            for (Iterator j = jh.getObjects(); j.hasNext();)
            {
                Object next2 = j.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingObjects, run))
                    continue;
                JavaObject jo = (JavaObject) next2;

                if (++objProgress % workObjectsStep == 0)
                {
                    listener.worked(1);
                    workCountSoFar += 1;
                    if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
                }

                long objAddress = jo.getID().getAddress();
                objAddress = fixBootLoaderAddress(bootLoaderAddress, objAddress);

                rememberObject(jo, objAddress, allClasses, listener);
            }
        }
        final int nobj = indexToAddress.size() - s2;
        debugPrint("Objects on heap " + nobj); //$NON-NLS-1$

        // Find any more classes (via the class loaders), and remember them
        // This might not be needed - all the classes cached should be found in
        // the defined list of some loader
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingClassesCachedByClassLoaders);
        for (Iterator i = run.getJavaClassLoaders(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingClassLoaders, run))
                continue;
            if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
            JavaClassLoader jcl = (JavaClassLoader) next;
            for (Iterator j = jcl.getCachedClasses(); j.hasNext();)
            {
                Object next2 = j.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingCachedClasses, jcl))
                    continue;
                JavaClass j2 = (JavaClass) next2;
                if (!allClasses.contains(j2))
                {
                    try
                    {
                        String className = j2.getName();
                        listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_AddingExtraClassViaCachedList, className), null);
                    }
                    catch (CorruptDataException e)
                    {
                        listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_AddingExtraClassOfUnknownNameViaCachedList, j2), e);
                    }
                }
                allClasses.add(j2);
            }
        }

        // Make the ID to address array ready for reverse lookups
        if (indexToAddress.size() > 0)
        {
            indexToAddress.sort();
        }

        // See if thread, monitor and class loader objects are present in heap
        // core-sample-dmgr.dmp.zip
        HashMap<Long, JavaObject> missingObjects = new HashMap<Long, JavaObject>();
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingThreadObjectsMissingFromHeap);
        for (Iterator i = run.getThreads(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingThreads, run))
                continue;
            JavaThread th = (JavaThread) next;
            try
            {
                JavaObject threadObject = th.getObject();
                // Thread object could be null if the thread is being attached
                long threadAddress = 0;
                if (threadObject != null)
                {
                    threadAddress = threadObject.getID().getAddress();
                    if (indexToAddress.reverse(threadAddress) < 0)
                    {
                        missingObjects.put(threadAddress, threadObject);
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ThreadObjectNotFound, format(threadAddress)), null);
                    }
                }
            }
            catch (CorruptDataException e)
            {
                try
                {
                    String name = th.getName();
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingJavaThreadInformationFor, name), e);
                }
                catch (CorruptDataException e2)
                {
                    listener.sendUserMessage(Severity.WARNING,
                                    Messages.DTFJIndexBuilder_ProblemReadingJavaThreadInformation, e);
                    listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_ProblemReadingJavaThreadName, e2);
                }
            }
        }
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingMonitorObjects);
        for (Iterator i = run.getMonitors(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingMonitors, run))
                continue;
            JavaMonitor jm = (JavaMonitor) next;
            JavaObject obj = jm.getObject();
            if (obj != null)
            {
                long monitorAddress = obj.getID().getAddress();
                if (indexToAddress.reverse(monitorAddress) < 0)
                {
                    missingObjects.put(monitorAddress, obj);
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_MonitorObjectNotFound, format(monitorAddress)), null);
                }
            }
        }
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingClassLoaderObjects);
        for (Iterator<JavaObject> i = loaders.keySet().iterator(); i.hasNext();)
        {
            JavaObject obj = i.next();
            if (obj != null)
            {
                long loaderAddress = obj.getID().getAddress();
                loaderAddress = fixBootLoaderAddress(bootLoaderAddress, loaderAddress);
                if (indexToAddress.reverse(loaderAddress) < 0)
                {
                    missingObjects.put(loaderAddress, obj);
                    try
                    {
                        String type = getClassName(obj.getJavaClass(), listener);
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ClassLoaderObjectNotFoundType, format(loaderAddress),
                                        type), null);
                    }
                    catch (CorruptDataException e)
                    {
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ClassLoaderObjectNotFound, format(loaderAddress)), e);
                    }

                }
            }
        }
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_AddingMissingObjects);
        for (Iterator<JavaObject> i = missingObjects.values().iterator(); i.hasNext();)
        {
            JavaObject obj = i.next();
            long address = obj.getID().getAddress();
            rememberObject(obj, address, allClasses, listener);
        }

        // Make the ID to address array ready for reverse lookups
        if (indexToAddress.size() > 0)
        {
            indexToAddress.sort();
        }

        // Temporary list for classes
        IndexWriter.Identifier indexToAddressCls = new IndexWriter.Identifier();

        //
        JavaClass clsJavaLangClassLoader = null;
        // Total all the classes and remember the addresses for mapping to IDs
        for (JavaClass cls : allClasses)
        {
            String clsName = null;
            try
            {
                // Get the name - if we cannot get the name then the class can
                // not be built.
                clsName = getClassName(cls, listen);
                // Find java.lang.ClassLoader. There should not be duplicates.
                if (clsJavaLangClassLoader == null && clsName.equals("java/lang/ClassLoader")) //$NON-NLS-1$
                    clsJavaLangClassLoader = cls;
                long clsaddr = getClassAddress(cls, listener);
                /*
                 * IBM Java 5.0 seems to have JavaClass at the same address as
                 * the associated object, and these are outside the heap, so
                 * need to be counted. IBM Java 6 seems to have JavaClass at a
                 * different address to the associated object and the associated
                 * object is already in the heap, so will have been found
                 * already. IBM Java 1.4.2 can have classes without associated
                 * objects. These won't be on the heap so should be added now.
                 * The other class objects have the same address as the real
                 * objects and are listed in the heap. If the id is null then
                 * the object will be too. Double counting is bad.
                 */
                if (indexToAddress.reverse(clsaddr) < 0)
                {
                    // JavaClass == JavaObject, so add the class (which isn't on
                    // the heap) to the list
                    indexToAddressCls.add(clsaddr);
                    debugPrint("adding class " + clsName + " at " + format(clsaddr) + " to the identifier list"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                else
                {
                    debugPrint("skipping class " + clsName + " at " + format(clsaddr) //$NON-NLS-1$ //$NON-NLS-2$
                                    + " as the associated object is already on the identifier list"); //$NON-NLS-1$
                }
            }
            catch (CorruptDataException e)
            {
                if (clsName == null)
                    clsName = cls.toString();
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemFindingObjectAddress, clsName), e);
            }
        }

        // Add class ids to object list
        for (int i = 0; i < indexToAddressCls.size(); ++i)
        {
            indexToAddress.add(indexToAddressCls.get(i));
        }
        // Free the class address list for GC
        indexToAddressCls = null;

        int nClasses = allClasses.size();

        // Make the ID to address array ready for reverse lookups
        if (indexToAddress.size() > 0)
        {
            indexToAddress.sort();
        }
        // Notify the builder about all the identifiers.
        index.setIdentifiers(indexToAddress);
        listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                        Messages.DTFJIndexBuilder_FoundIdentifiersObjectsClasses, indexToAddress.size(), indexToAddress
                                        .size()
                                        - nClasses, nClasses), null);
        debugPrint("Total identifiers " + indexToAddress.size()); //$NON-NLS-1$

        if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }

        // Pass 2 - build the classes and object data
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_Pass2);
        debugPrint("Classes " + nClasses); //$NON-NLS-1$
        idToClass = new HashMapIntObject<ClassImpl>(nClasses);

        // For calculating purge sizes
        objectToSize = new ObjectToSize(indexToAddress.size());

        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_BuildingClasses);

        ClassImpl jlc = null;
        JavaClass clsJavaClass = null;
        for (JavaClass j2 : allClasses)
        {
            // First find the class obj for java.lang.Class
            // This is needed for every other class
            try
            {
                JavaObject clsObject = j2.getObject();
                if (clsObject != null)
                {
                    clsJavaClass = clsObject.getJavaClass();
                    jlc = genClass(clsJavaClass, idToClass, bootLoaderAddress, 0, listener);
                    genClass2(clsJavaClass, jlc, jlc, pointerSize, listener);
                    // Found class, so done
                    break;
                }
            }
            catch (IllegalArgumentException e)
            {
                // IllegalArgumentException from
                // JavaClass.getObject() due to bad class pointer in object
                listener.sendUserMessage(Severity.ERROR, Messages.DTFJIndexBuilder_ProblemFindingJavaLangClass, e);
            }
            catch (CorruptDataException e)
            {
                if (msgNcorruptCount-- > 0)
                    listener
                                    .sendUserMessage(Severity.WARNING,
                                                    Messages.DTFJIndexBuilder_ProblemFindingJavaLangClass, e);
            }
        }
        // Plan B to find java.lang.Class
        if (clsJavaClass == null)
            for (JavaClass j2 : allClasses)
            {
                // First find the class obj for java.lang.Class
                // This is needed for every other class
                try
                {
                    String cn = j2.getName();
                    if ("java/lang/Class".equals(cn)) //$NON-NLS-1$
                    {
                        clsJavaClass = j2;
                        jlc = genClass(clsJavaClass, idToClass, bootLoaderAddress, 0, listener);
                        genClass2(clsJavaClass, jlc, jlc, pointerSize, listener);
                        debugPrint("Found java.lang.Class " + cn); //$NON-NLS-1$
                        // Found class, so done
                        break;
                    }
                }
                catch (CorruptDataException e)
                {
                    listener.sendUserMessage(Severity.WARNING,
                                    Messages.DTFJIndexBuilder_ProblemFindingJavaLangClassViaName, e);
                }
            }

        // Now do java.lang.ClassLoader
        ClassImpl jlcl = clsJavaLangClassLoader != null ? genClass(clsJavaLangClassLoader, idToClass, bootLoaderAddress, 0, listener) : null;
        if (jlcl != null)
        {
            genClass2(clsJavaLangClassLoader, jlcl, jlc, pointerSize, listener);
        }

        boolean foundFields = false;
        for (JavaClass j2 : allClasses)
        {
            // Don't do java.lang.Class twice
            if (j2.equals(clsJavaClass))
                continue;
            // Don't do java.lang.ClassLoader twice
            if (j2.equals(clsJavaLangClassLoader))
                continue;

            // Fix for PHD etc without superclasses
            // so make class loader types extend java.lang.ClassLoader
            long newSuper = 0;
            if (jlcl != null && loaderTypes.contains(j2))
            {
                JavaClass sup = getSuperclass(j2, listener);
                if (sup == null || getSuperclass(sup, listener) == null)
                {
                    newSuper = jlcl.getObjectAddress();
                }
            }

            ClassImpl ci = genClass(j2, idToClass, bootLoaderAddress, newSuper, listener);
            if (ci != null)
            {
                genClass2(j2, ci, jlc, pointerSize, listener);
                // See if any fields have been found, or whether we need to use
                // getReferences instead
                if (!foundFields)
                {
                    List<FieldDescriptor> fd = ci.getFieldDescriptors();
                    if (!fd.isEmpty())
                        foundFields = true;
                }
            }
        }

        if (bootLoaderObject == null)
        {
            // If there is no boot loader type,
            bootLoaderType = jlcl;
            // The bootLoaderType should always have been found by now, so
            // invent something now to avoid NullPointerExceptions.
            if (bootLoaderType == null)
                bootLoaderType = idToClass.values().next();
        }

        // If none of the classes have any fields then we have to try using
        // references instead.
        if (!foundFields)
        {
            // E.g. PHD dumps
            haveDTFJRefs = true;
            useDTFJRefs = true;
        }

        // fix up the subclasses for MAT
        int maxClsId = 0;
        for (Iterator<ClassImpl> i = idToClass.values(); i.hasNext();)
        {
            ClassImpl ci = i.next();
            int supid = ci.getSuperClassId();
            if (supid >= 0)
            {
                ClassImpl sup = idToClass.get(supid);
                if (sup != null)
                {
                    sup.addSubClass(ci);
                }
            }
            maxClsId = Math.max(maxClsId, ci.getObjectId());
        }
        // Notify the builder about the classes. The builder seems to destroy
        // entries which are unreachable.
        index.setClassesById(idToClass);

        // See which classes would have finalizable objects
        HashSet<Long> finalizableClass;
        if (guessFinalizables)
        {
            finalizableClass = new HashSet<Long>();
            for (JavaClass cls : allClasses)
            {
                long addr = isFinalizable(cls, listener);
                if (addr != 0)
                    finalizableClass.add(addr);
            }
        }

        // Object id to class id
        objectToClass = new IndexWriter.IntIndexCollector(indexToAddress.size(), IndexWriter
                        .mostSignificantBit(maxClsId));

        // Do the object refs to other refs
        IOne2ManyIndex out2b;
        outRefs = new IndexWriter.IntArray1NWriter(indexToAddress.size(), IndexManager.Index.OUTBOUND.getFile(pfx
                        + "temp.")); //$NON-NLS-1$

        // Keep track of all objects which are referred to. Remaining objects
        // are candidate roots
        boolean refd[] = new boolean[indexToAddress.size()];

        // fix up type of class objects
        for (Iterator<ClassImpl> i = idToClass.values(); i.hasNext();)
        {
            ClassImpl ci = i.next();
            int clsId = ci.getClassId();
            int objId = ci.getObjectId();
            objectToClass.set(objId, clsId);
        }

        // check outbound refs
        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingOutboundReferencesForClasses);
        // 10% of remaining bar for the classes processing
        final int classFrac = 3;
        final int workObjectsStep2 = Math.max(1, classFrac * allClasses.size() / (workCount - workCountSoFar));
        final int work2 = (workCount - workCountSoFar) * workObjectsStep2 / (classFrac * allClasses.size() + 1);
        // Classes processed in address order (via TreeSet) so PHD reading is
        // cache friendly.
        for (JavaClass j2 : allClasses)
        {
            String clsName = null;
            try
            {
                long claddr = getClassAddress(j2, listener);
                int objId = indexToAddress.reverse(claddr);
                if (++objProgress % workObjectsStep2 == 0)
                {
                    listener.worked(work2);
                    workCountSoFar += work2;
                    if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
                }
                ClassImpl ci = idToClass.get(objId);
                if (debugInfo) debugPrint("Class " + getClassName(j2, listener) + " " + format(claddr) + " " + objId + " " + ci); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                if (ci == null)
                {
                    // Perhaps the class was corrupt and never built
                    continue;
                }
                int clsId = ci.getClassId();
                clsName = ci.getName();
                debugPrint("found class object " + objId + " type " + clsName + " at " + format(ci.getObjectAddress()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                + " clsId " + clsId); //$NON-NLS-1$
                ArrayLong ref = ci.getReferences();
                // Constant pool references have already been set up as pseudo
                // fields
                if (false)
                    for (Iterator i2 = j2.getConstantPoolReferences(); i2.hasNext();)
                    {
                        Object next = i2.next();
                        if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingConstantPool, j2))
                            continue;
                        if (next instanceof JavaObject)
                        {
                            JavaObject jo = (JavaObject) next;
                            long address = jo.getID().getAddress();
                            ref.add(address);
                        }
                        else if (next instanceof JavaClass)
                        {
                            JavaClass jc = (JavaClass) next;
                            long address = getClassAddress(jc, listener);
                            ref.add(address);
                        }
                    }
                // Superclass address are now added by getReferences()
                // long supAddr = ci.getSuperClassAddress();
                // if (supAddr != 0) ref.add(ci.getSuperClassAddress());
                for (IteratorLong il = ref.iterator(); il.hasNext();)
                {
                    long ad = il.next();
                    if (false)
                        debugPrint("ref to " + indexToAddress.reverse(ad) + " " + format(ad) + " for " + objId); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                try
                {
                    checkRefs(j2, Messages.DTFJIndexBuilder_CheckRefsClass, ref, clsJavaClass, bootLoaderAddress,
                                    listener);
                }
                catch (CorruptDataException e)
                {
                    if (clsName == null)
                        clsName = j2.toString();
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemCheckingOutboundReferencesForClass, clsName), e);
                }

                // fix up outbound refs for ordinary classes
                addRefs(refd, ref);
                outRefs.log(indexToAddress, objId, ref);
            }
            catch (CorruptDataException e)
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemFindingObjectAddress, clsName), e);
            }
        }

        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingRoots);

        // Java 1.4.2 has bootLoader as null and the address of the Java stack
        // frame at the lower memory address
        boolean scanUp = bootLoaderAddress == 0;

        boolean goodDTFJRoots = processDTFJRoots(pointerSize, listener);

        // Used to keep track of what extra stuff DTFJ gives
        HashSet<Integer> prevRoots = rootSet();
        HashSet<Integer> newRoots;

        if (!goodDTFJRoots)
        {
            workCountSoFar = processConservativeRoots(pointerSize, fixedBootLoaderAddress, scanUp, workCountSoFar,
                            listener);
            missedRoots = addMissedRoots(missedRoots);
            newRoots = rootSet();
        }
        else if (verbose)
        {
            // Just for debugging. We are going to use DTFJ roots, but want to
            // see what conservative GC would give.
            HashMapIntObject<List<XGCRootInfo>> gcRoot2 = gcRoot;
            HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> threadRoots2 = threadRoots;
            workCountSoFar = processConservativeRoots(pointerSize, fixedBootLoaderAddress, scanUp, workCountSoFar,
                            listener);
            missedRoots = addMissedRoots(missedRoots);
            newRoots = rootSet();
            // Restore DTFJ Roots
            gcRoot = gcRoot2;
            threadRoots = threadRoots2;
        }
        else
        {
            newRoots = prevRoots;
        }

        HashSet<Integer> newRoots2 = newRoots;

        // Debug - find the roots which DTFJ missed
        newRoots.removeAll(prevRoots);
        for (int i : newRoots)
        {
            debugPrint("DTFJ Roots missed object id " + i + " " + format(indexToAddress.get(i)) + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + missedRoots.get(i));
        }

        // Debug - find the roots which only DTFJ found
        prevRoots.removeAll(newRoots2);
        for (int i : prevRoots)
        {
            debugPrint("DTFJ Roots has extra object id " + i + " " + format(indexToAddress.get(i)) + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + missedRoots.get(i));
        }

        // Mark everything - for debug
        if (false)
            for (int i = 0; i < indexToAddress.size(); ++i)
            {
                long addr = indexToAddress.get(i);
                addRoot(gcRoot, addr, fixedBootLoaderAddress, GCRootInfo.Type.UNKNOWN);
            }

        IndexWriter.IntIndexCollectorUncompressed ic2 = new IndexWriter.IntIndexCollectorUncompressed(indexToAddress
                        .size());

        listener.worked(1);
        workCountSoFar += 1;
        listener.subTask(Messages.DTFJIndexBuilder_FindingOutboundReferencesForObjects);

        loaderClassCache = initLoaderClassesCache();

        int objProgress2 = 0;
        // Find all the objects
        for (Iterator i = run.getHeaps(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingHeaps, run))
                continue;
            JavaHeap jh = (JavaHeap) next;
            for (Iterator j = jh.getObjects(); j.hasNext();)
            {
                Object next2 = j.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingObjects, run))
                    continue;
                JavaObject jo = (JavaObject) next2;

                if (++objProgress2 % workObjectsStep == 0)
                {
                    // Progress monitoring
                    int workDone = workObjectsStep * (workCount - workCountSoFar)
                                    / (workObjectsStep + nobj - objProgress2);
                    debugPrint("workCount=" + workCountSoFar + "/" + workCount + " objects=" + objProgress2 + "/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                    + nobj + " " + workDone); //$NON-NLS-1$
                    listener.worked(workDone);
                    workCountSoFar += workDone;
                    if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
                }

                processHeapObject(jo, pointerSize, bootLoaderAddress, loaders, jlc, clsJavaClass, refd, ic2, listener);
            }
        }
        // Objects not on the heap
        for (Iterator<JavaObject> i = missingObjects.values().iterator(); i.hasNext();)
        {
            JavaObject jo = i.next();
            processHeapObject(jo, pointerSize, bootLoaderAddress, loaders, jlc, clsJavaClass, refd, ic2, listener);
        }

        // Boot Class Loader
        if (bootLoaderObject == null)
        {
            // To accumulate the outbound refs
            ArrayLong aa = new ArrayLong();
            // Add a reference to the class
            aa.add(bootLoaderType.getClassAddress());
            int objId = indexToAddress.reverse(fixedBootLoaderAddress);
            addLoaderClasses(objId, aa);
            try
            {
                checkRefs(bootLoaderObject, Messages.DTFJIndexBuilder_CheckRefsBootLoader, aa, clsJavaClass,
                                bootLoaderAddress, listener);
            }
            catch (CorruptDataException e)
            {
                listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_ProblemCheckingBootLoaderReferences,
                                e);
            }
            addRefs(refd, aa);
            outRefs.log(indexToAddress, objId, aa);
            // If there are no instances of ClassLoader then the size is
            // unknown, so set it to zero?
            if (bootLoaderType.getHeapSizePerInstance() == -1)
                bootLoaderType.setHeapSizePerInstance(0);
            bootLoaderType.addInstance(bootLoaderType.getHeapSizePerInstance());
            objectToClass.set(objId, bootLoaderType.getObjectId());
            // For calculating purge sizes
            objectToSize.set(objId, bootLoaderType.getHeapSizePerInstance());
        }

        index.setObject2classId(objectToClass);

        arrayToSize = ic2.writeTo(IndexManager.Index.A2SIZE.getFile(pfx + "temp.")); //$NON-NLS-1$
        index.setArray2size(arrayToSize);

        out2b = outRefs.flush();
        // flush doesn't clear an internal array
        outRefs = null;
        index.setOutbound(out2b);

        // Missing finalizables from XML and GC roots
        // All objects with a finalize method which are not reached from
        // another object are guessed as being 'finalizable'.
        if (guessFinalizables && !(goodDTFJRoots && foundFinalizableGCRoots))
        {
            int finalizables = 0;
            listener.subTask(Messages.DTFJIndexBuilder_GeneratingExtraRootsFromFinalizables);
            for (int i = 0; i < refd.length; ++i)
            {
                if (!refd[i])
                {
                    int clsId = objectToClass.get(i);
                    long clsAddr = indexToAddress.get(clsId);
                    ClassImpl classInfo = idToClass.get(clsId);
                    String clsInfo;
                    // If objectToClass has not yet been filled in for objects
                    // then this could be null
                    if (classInfo != null)
                    {
                        clsInfo = classInfo.getName();
                    }
                    else
                    {
                        clsInfo = format(clsAddr);
                    }
                    if (finalizableClass.contains(clsAddr))
                    {
                        long addr = indexToAddress.get(i);
                        if (!gcRoot.containsKey(i))
                        {
                            // Make a root as this object is not referred to nor
                            // a normal root, but has a finalize method
                            // This ensures that all finalizable objects are
                            // retained (except isolated cycles),
                            ++finalizables;
                            addRoot(gcRoot, addr, fixedBootLoaderAddress, GCRootInfo.Type.FINALIZABLE);
                            refd[i] = true;
                            if (msgNguessFinalizable-- > 0)
                                listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_ObjectIsFinalizable, clsInfo, format(addr)),
                                                null);
                            debugPrint("extra finalizable root " + i + " " + format(addr)); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
            listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                            Messages.DTFJIndexBuilder_FinalizableObjectsMarkedAsRoots, finalizables), null);
        }

        // Remaining roots
        if (gcRoot.isEmpty() || threadRoots.isEmpty() || threadRootObjects() == 0 || presumeRoots)
        {
            listener.subTask(Messages.DTFJIndexBuilder_GeneratingExtraRootsMarkingAllUnreferenced);
            int extras = 0;
            for (int i = 0; i < refd.length; ++i)
            {
                if (!refd[i])
                {
                    long addr = indexToAddress.get(i);
                    if (!gcRoot.containsKey(i))
                    {
                        // Make a root as this object is not referred to nor a
                        // normal root
                        // This ensures that all objects are retained (except
                        // isolated cycles),
                        // just in case the approximate roots miss something
                        // important
                        ++extras;
                        addRoot(gcRoot, addr, fixedBootLoaderAddress, GCRootInfo.Type.UNKNOWN);
                        refd[i] = true;
                        debugPrint("extra root " + i + " " + format(addr)); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
            if (markAllLoaders)
            {
                for (JavaObject lo : loaders.keySet())
                {
                    long addr = lo.getID().getAddress();
                    int i = indexToAddress.reverse(addr);
                    if (i >= 0)
                    {
                        if (!gcRoot.containsKey(i))
                        {
                            // Make a root as this class loader might not be
                            // marked.
                            // The loader will be in a cycle with its classes.
                            ++extras;
                            addRoot(gcRoot, addr, fixedBootLoaderAddress, GCRootInfo.Type.UNKNOWN);
                            refd[i] = true;
                            debugPrint("extra root " + i + " " + format(addr)); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
            listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                            Messages.DTFJIndexBuilder_UnreferenceObjectsMarkedAsRoots, extras), null);
        }

        index.setGcRoots(gcRoot);
        index.setThread2objects2roots(threadRoots);
        if (goodDTFJRoots)
        {
            String msg = MessageFormat.format(Messages.DTFJIndexBuilder_UsingDTFJRoots, gcRoot.size());
            listener.sendUserMessage(Severity.INFO, msg, null);
            debugPrint(msg);
        }
        else
        {
            String msg = MessageFormat.format(Messages.DTFJIndexBuilder_UsingConservativeGarbageCollectionRoots, gcRoot
                            .size());
            listener.sendUserMessage(Severity.WARNING, msg, null);
            debugPrint(msg);
        }

        if (verbose)
        {
            // For identifying purged objects
            idToClass2 = copy(idToClass);
            objectToClass2 = copy(objectToClass, IndexWriter.mostSignificantBit(maxClsId));
        }

        // If a message count goes below zero then a message has not been
        // printed
        int skippedMessages = 0;
        skippedMessages += Math.max(0, 0);
        skippedMessages += Math.max(0, -msgNgetRefsMissing);
        skippedMessages += Math.max(0, -msgNgetRefsExtra);
        skippedMessages += Math.max(0, -msgNarrayRefsNPE);
        skippedMessages += Math.max(0, -msgNgetRefsUnavailable);
        skippedMessages += Math.max(0, -msgNgetRefsCorrupt);
        skippedMessages += Math.max(0, -msgNbigSegs);
        skippedMessages += Math.max(0, -msgNinvalidArray);
        skippedMessages += Math.max(0, -msgNinvalidObj);
        skippedMessages += Math.max(0, -msgNbrokenEquals);
        skippedMessages += Math.max(0, -msgNbrokenInterfaceSuper);
        skippedMessages += Math.max(0, -msgNmissingLoaderMsg);
        skippedMessages += Math.max(0, -msgNcorruptCount);
        skippedMessages += Math.max(0, -msgNrootsWarning);
        skippedMessages += Math.max(0, -msgNguessFinalizable);
        skippedMessages += Math.max(0, -msgNgetRefsAllMissing);
        skippedMessages += Math.max(0, -msgNgetSuperclass);
        skippedMessages += Math.max(0, -msgNnullThreadObject);
        skippedMessages += Math.max(0, -msgNbadThreadInfo);
        skippedMessages += Math.max(0, -msgNunexpectedModifiers);
        skippedMessages += Math.max(0, -msgNcorruptSection);
        skippedMessages += Math.max(0, -msgNclassForObject);
        skippedMessages += Math.max(0, -msgNtypeForClassObject);
        skippedMessages += Math.max(0, -msgNobjectSize);
        skippedMessages += Math.max(0, -msgNoutboundReferences);
        if (skippedMessages > 0)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_RepeatedMessagesSuppressed, skippedMessages), null);
        }

        long now2 = System.currentTimeMillis();
        listener.sendUserMessage(Severity.INFO, MessageFormat.format(Messages.DTFJIndexBuilder_TookmsToParseFile,
                        (now2 - now1), dump), null);
        validateIndices(listener);
        // Free some memory
        gcRoot = null;
        threadRoots = null;
        image = null;
        run = null;
        dummyClassAddress = null;
        dummyMethodAddress = null;
        methodAddresses = null;
        loaderClassCache = null;
        listener.done();
    }

    /**
     * Create a file to store all of the thread stack information.
     * Close the PrintWriter when done.
     * @return a PrintWriter used to store information in the file
     */
    private PrintWriter createThreadInfoFile()
    {
        PrintWriter pw = null;
        try
        {
            // Used to store thread stack information
            pw = new PrintWriter(new FileWriter(pfx + "threads")); //$NON-NLS-1$
        }
        catch (IOException e)
        {}
        return pw;
    }

    /**
     * Write out information about this thread and its thread stack so that MAT
     * can retrieve the information later
     * @param out where to write out the data
     * @param th the thread in question
     */
    private void printThreadStack(PrintWriter out, JavaThread th)
    {
        try {
            JavaObject jo = th.getObject();
            out.print("Thread "); //$NON-NLS-1$
            out.println(jo != null ? "0x"+Long.toHexString(jo.getID().getAddress()) : "<unknown>"); //$NON-NLS-1$ //$NON-NLS-2$
            for (Iterator it = th.getStackFrames(); it.hasNext(); out.println())
            {
                Object next = it.next();
                out.print(" at "); //$NON-NLS-1$
                if (next instanceof CorruptData)
                {
                    continue;
                }
                JavaStackFrame jsf = (JavaStackFrame) next;
                try
                {
                    JavaLocation jl = jsf.getLocation();
                    try
                    {
                        JavaMethod jm = jl.getMethod();
                        try
                        {
                            JavaClass jc = jm.getDeclaringClass();
                            out.print(jc.getName().replace("/", ".")); //$NON-NLS-1$ //$NON-NLS-2$
                            out.print("."); //$NON-NLS-1$
                        }
                        catch (CorruptDataException e)
                        {}
                        catch (DataUnavailable e)
                        {}
                        try
                        {
                            out.print(jm.getName());
                        }
                        catch (CorruptDataException e)
                        {}
                        try
                        {
                            out.print(jm.getSignature());
                        }
                        catch (CorruptDataException e)
                        {}
                        out.print(" "); //$NON-NLS-1$
                        try
                        {
                            if (Modifier.isNative(jm.getModifiers()))
                            {
                                out.print("(Native Method)"); //$NON-NLS-1$
                                continue;
                            }
                        }
                        catch (CorruptDataException e)
                        {}
                    }
                    catch (CorruptDataException e)
                    {}
                    try
                    {
                        out.print("("); //$NON-NLS-1$
                        out.print(jl.getFilename());
                        try
                        {
                            out.print(":" + jl.getLineNumber()); //$NON-NLS-1$
                        }
                        catch (CorruptDataException e)
                        {}
                        catch (DataUnavailable e)
                        {}
                        out.print(")"); //$NON-NLS-1$
                    }
                    catch (DataUnavailable e)
                    {
                        out.print("Unknown Source)"); //$NON-NLS-1$
                    }
                    catch (CorruptDataException e)
                    {
                        out.print("Unknown Source)"); //$NON-NLS-1$
                    }
                }
                catch (CorruptDataException e)
                {}
            }
        } catch (CorruptDataException e) {
            
        }
        out.println();
        out.println(" locals:"); //$NON-NLS-1$
    }
    
    /**
     * Print out a single local variable for thread stack data
     * @param pw where to store the information
     * @param target the address of the object
     * @param frameNum the Java stack frame, starting from 0 at top of stack
     */
    private void printLocal(PrintWriter pw, long target, int frameNum)
    {
        if (indexToAddress.reverse(target) >= 0)
            pw.println("  objecId=0x" + Long.toHexString(target) + ", line=" //$NON-NLS-1$ //$NON-NLS-2$
                            + frameNum);
    }
    
    private int processConservativeRoots(int pointerSize, long fixedBootLoaderAddress, boolean scanUp,
                    int workCountSoFar, IProgressListener listener)
    {
        gcRoot = new HashMapIntObject<List<XGCRootInfo>>();

        listener.subTask(Messages.DTFJIndexBuilder_GeneratingSystemRoots);

        /*
         * There isn't actually a need to mark the system classes as a thread
         * marks java/lang/Thread, which marks the boot loader, which marks all
         * the classes. Other parsers do mark them, so this simulates that
         * behaviour.
         */
        if (useSystemClassRoots)
        {
            // Mark the boot class loader itself - is this required?
            addRoot(gcRoot, fixedBootLoaderAddress, fixedBootLoaderAddress, GCRootInfo.Type.SYSTEM_CLASS);
            for (Iterator<ClassImpl> i = idToClass.values(); i.hasNext();)
            {
                ClassImpl ci = i.next();

                // Should we mark all system classes?
                if (ci.getClassLoaderAddress() == fixedBootLoaderAddress)
                {
                    addRoot(gcRoot, ci.getObjectAddress(), ci.getClassLoaderAddress(), GCRootInfo.Type.SYSTEM_CLASS);
                }
            }
        }

        listener.subTask(Messages.DTFJIndexBuilder_GeneratingThreadRoots);

        PrintWriter pw = createThreadInfoFile();
        
        threadRoots = new HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>>();
        for (Iterator i = run.getThreads(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingThreads, run))
                continue;
            JavaThread th = (JavaThread) next;
            listener.worked(1);
            workCountSoFar += 1;
            if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
            try
            {
                JavaObject threadObject = th.getObject();
                long threadAddress = 0;
                // Thread object could be null if the thread is being attached
                if (threadObject != null)
                {
                    threadAddress = threadObject.getID().getAddress();

                    // CorruptDataException from
                    // deadlock/xa64/j9/core.20071025.dmp.zip
                    try
                    {
                        int threadState = th.getState();
                        // debugPrint("Considering thread
                        // "+format(threadAddress)+"
                        // "+Integer.toBinaryString(threadState)+"
                        // "+th.getName());
                        if ((threadState & JavaThread.STATE_ALIVE) == 0)
                        {
                            // Ignore threads which are not alive
                            continue;
                        }
                    }
                    catch (CorruptDataException e)
                    {
                        listener.sendUserMessage(Severity_INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ThreadStateNotFound, format(threadAddress)), e);
                        // debugPrint("Considering thread
                        // "+format(threadAddress)+" as state unavailable");
                    }
                    catch (IllegalArgumentException e)
                    {
                        // IllegalArgumentException from
                        // Thread.getName()
                        listener.sendUserMessage(Severity_INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ThreadNameNotFound, format(threadAddress)), e);
                        // debugPrint("Considering thread
                        // "+format(threadAddress)+" as state unavailable");
                    }

                    // Make the thread a proper GC Root
                    addRoot(gcRoot, threadAddress, fixedBootLoaderAddress, GCRootInfo.Type.THREAD_OBJ);

                    int threadID = indexToAddress.reverse(threadAddress);
                    HashMapIntObject<List<XGCRootInfo>> thr = new HashMapIntObject<List<XGCRootInfo>>();
                    threadRoots.put(threadID, thr);
                }
                else
                {
                    // Null thread object
                    Exception e1;
                    long jniEnvAddress;
                    String name = ""; //$NON-NLS-1$
                    try
                    {
                        name = th.getName();
                        jniEnvAddress = th.getJNIEnv().getAddress();
                        e1 = null;
                    }
                    catch (CorruptDataException e)
                    {
                        jniEnvAddress = 0;
                        e1 = e;
                    }
                    if (msgNnullThreadObject-- > 0)
                        listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ThreadObjectNotFoundSoIgnoring, name,
                                        format(jniEnvAddress)), e1);
                }

                scanJavaThread(th, threadAddress, pointerSize, threadRoots, listener, scanUp, pw);
                try
                {
                    ImageThread it = th.getImageThread();
                    scanImageThread(th, it, threadAddress, pointerSize, threadRoots, listener);
                }
                catch (DataUnavailable e)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_NativeThreadNotFound, format(threadAddress)), e);
                }
            }
            catch (CorruptDataException e)
            {
                if (msgNbadThreadInfo-- > 0)
                    listener.sendUserMessage(Severity.WARNING,
                                    Messages.DTFJIndexBuilder_ProblemReadingThreadInformation, e);
            }
        }
        if (pw != null)
        {
            pw.close();
        }

        // Monitor GC roots
        listener.subTask(Messages.DTFJIndexBuilder_GeneratingMonitorRoots);

        for (Iterator i = run.getMonitors(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingMonitors, run))
                continue;
            JavaMonitor jm = (JavaMonitor) next;
            JavaObject obj = jm.getObject();
            if (obj != null)
            {
                // Make the monitored object a root
                try
                {
                    JavaThread jt = jm.getOwner();
                    if (jt != null)
                    {
                        // Unowned monitors do not keep objects alive
                        addRootForThread(obj, jt, listener);
                    }
                }
                catch (CorruptDataException e)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnableToFindThreadOwningMonitor, format(jm.getID()
                                                    .getAddress()), format(obj.getID().getAddress())), e);
                    // Play safe and add as a global root
                    addRootForThread(obj, null, listener);
                }
                // Is there any need to mark enter waiters or notify waiters
                // Surely the object is also a local variable, but perhaps local
                // variable information is incorrect.
                addRootForThreads(obj, jm.getEnterWaiters(), listener);
                addRootForThreads(obj, jm.getNotifyWaiters(), listener);
            }
        }
        return workCountSoFar;
    }

    private boolean processDTFJRoots(int pointerSize, IProgressListener listener)
    {
        boolean goodDTFJRoots = false;
        if (haveDTFJRoots)
        {
            listener.subTask(Messages.DTFJIndexBuilder_FindingRootsFromDTFJ);
            debugPrint("DTFJ roots"); //$NON-NLS-1$

            HashMapIntObject<List<XGCRootInfo>> gcRoot2;
            gcRoot2 = new HashMapIntObject<List<XGCRootInfo>>();
            HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> threadRoots2;
            threadRoots2 = new HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>>();

            goodDTFJRoots = true;

            // For debug
            missedRoots = new HashMap<Integer, String>();

            // See if the heap roots support is even in DTFJ
            Iterator it;
            try
            {
                it = run.getHeapRoots();
                // Javacore reader returns null
                if (it == null)
                {
                    it = Collections.EMPTY_LIST.iterator();
                    listener.sendUserMessage(Severity_WARNING, Messages.DTFJIndexBuilder_DTFJgetHeapRootsReturnsNull,
                                    null);
                }
            }
            catch (LinkageError e)
            {
                goodDTFJRoots = false;
                it = Collections.EMPTY_LIST.iterator();
                listener.sendUserMessage(Severity_WARNING, Messages.DTFJIndexBuilder_DTFJDoesNotSupportHeapRoots, e);
            }

            // True heap roots using DTFJ
            if (goodDTFJRoots)
            {
                listener.subTask(Messages.DTFJIndexBuilder_GeneratingGlobalRoots);
                debugPrint("Processing global roots"); //$NON-NLS-1$
                for (; it.hasNext();)
                {
                    Object next = it.next();
                    if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingRoots, run))
                        continue;
                    JavaReference r = (JavaReference) next;
                    processRoot(r, null, gcRoot2, threadRoots2, pointerSize, listener);
                }
                listener.subTask(Messages.DTFJIndexBuilder_GeneratingThreadRoots);
                debugPrint("Processing thread roots"); //$NON-NLS-1$
                PrintWriter pw = null;
                if (gcRoot2.size() > 0)
                {
                	pw = createThreadInfoFile();
                }
                long prevFrameAddress = 0;
                for (Iterator thit = run.getThreads(); thit.hasNext();)
                {
                    Object next = thit.next();
                    if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingThreads, run))
                        continue;
                    JavaThread th = (JavaThread) next;

                    if (pw != null)
                    {
                        // For thread stack information
                        printThreadStack(pw, th);
                    }
                    int frameNum = 0;
                    for (Iterator ii = th.getStackFrames(); ii.hasNext(); ++frameNum)
                    {
                        Object next2 = ii.next();
                        if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingJavaStackFrames,
                                        th))
                            continue;
                        JavaStackFrame jf = (JavaStackFrame) next2;
                        // - getHeapRoots returns null
                        if (jf.getHeapRoots() == null)
                        {
                            if (msgNrootsWarning-- > 0)
                                listener.sendUserMessage(Severity_WARNING,
                                                Messages.DTFJIndexBuilder_DTFJgetHeapRootsFromStackFrameReturnsNull,
                                                null);
                            continue;
                        }
                        for (Iterator i3 = jf.getHeapRoots(); i3.hasNext();)
                        {
                            Object next3 = i3.next();
                            if (isCorruptData(next3, listener, Messages.DTFJIndexBuilder_CorruptDataReadingRoots, run))
                                continue;
                            JavaReference r = (JavaReference) next3;
                            processRoot(r, th, gcRoot2, threadRoots2, pointerSize, listener);
                            if (pw != null)
                            {
                                // Details of the locals
                                try
                                {
                                    Object o = r.getTarget();
                                    if (o instanceof JavaObject)
                                    {
                                        JavaObject jo = (JavaObject) o;
                                        long target = jo.getID().getAddress();
                                        printLocal(pw, target, frameNum);
                                    }
                                    else if (o instanceof JavaClass)
                                    {
                                        JavaClass jc = (JavaClass) o;
                                        long target = getClassAddress(jc, listener);
                                        printLocal(pw, target, frameNum);
                                    }
                                }
                                catch (CorruptDataException e)
                                {}
                                catch (DataUnavailable e)
                                {}
                            }
                        }
                    }
                    if (pw != null)
                        pw.println();
                }
                if (pw != null)
                {
                    pw.close();
                }

                // We need some roots, so disable DTFJ roots if none are found
                if (gcRoot2.isEmpty())
                {
                    goodDTFJRoots = false;
                    listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_NoDTFJRootsFound, null);
                }

                if (!useDTFJRoots)
                {
                    goodDTFJRoots = false;
                    listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_DTFJRootsDisabled, null);
                }

                // The refd array is not affected by the GCroots

                // Still assign the gc roots even if the goodDTFJRoots is false
                // in case we want to see how good they were.
                gcRoot = gcRoot2;
                threadRoots = threadRoots2;
            }
        }
        return goodDTFJRoots;
    }

    private HashMap<Integer, String> addMissedRoots(HashMap<Integer, String> roots)
    {
        // Create information about roots we are not using
        if (roots == null)
            roots = new HashMap<Integer, String>();
        for (IteratorInt ii = gcRoot.keys(); ii.hasNext();)
        {
            int i = ii.next();
            List<XGCRootInfo> lr = gcRoot.get(i);
            String info = XGCRootInfo.getTypeSetAsString(lr.toArray(new XGCRootInfo[0]));
            String prev = roots.get(i);
            roots.put(i, prev != null ? prev + "," + info : info); //$NON-NLS-1$
        }
        for (int key : threadRoots.getAllKeys())
        {
            int oldRoots1[] = threadRoots.get(key).getAllKeys();
            for (int i : oldRoots1)
            {
                List<XGCRootInfo> lr = threadRoots.get(key).get(i);
                String info = XGCRootInfo.getTypeSetAsString(lr.toArray(new XGCRootInfo[0]));
                String prev = roots.get(i);
                roots.put(i, prev != null ? prev + "," + info : info); //$NON-NLS-1$
            }
        }
        return roots;
    }

    private HashSet<Integer> rootSet()
    {
        HashSet<Integer> prevRoots = new HashSet<Integer>();

        if (gcRoot != null)
        {
            int oldRoots[] = gcRoot.getAllKeys();
            for (int i : oldRoots)
            {
                prevRoots.add(i);
            }
            for (int key : threadRoots.getAllKeys())
            {
                oldRoots = threadRoots.get(key).getAllKeys();
                for (int i : oldRoots)
                {
                    prevRoots.add(i);
                }
            }
        }
        return prevRoots;
    }

    /**
     * Count the number of real objects which are thread roots from the stack.
     * If this is zero then we probably are missing GC roots.
     * @return Number of objects (not classes) marked as roots by threads.
     */
    private int threadRootObjects()
    {
        int objRoots = 0;
        // Look at each threads root
        for (Iterator<HashMapIntObject<List<XGCRootInfo>>> it = threadRoots.values(); it.hasNext();)
        {
            HashMapIntObject<List<XGCRootInfo>> hm = it.next();
            // Look at each object marked by a thread
            for (IteratorInt i2 = hm.keys(); i2.hasNext();)
            {
                int objId = i2.next();
                // If it is not a class then possibly count it
                if (!idToClass.containsKey(objId))
                {
                    for (Iterator<XGCRootInfo> i3 = hm.get(objId).iterator(); i3.hasNext(); )
                    {                        
                        int type = i3.next().getType();
                        // If it is a true local from a stack frame
                        if (type == GCRootInfo.Type.JAVA_LOCAL 
                            || type == GCRootInfo.Type.NATIVE_STACK
                            || type == GCRootInfo.Type.NATIVE_LOCAL)
                        {
                           ++objRoots;
                           break;
                        }
                    }
                }
            }
        }
        return objRoots;
    }

    /**
     * Record the object address in the list of identifiers and the associated
     * classes in the class list
     * 
     * @param jo
     * @param objAddress
     * @param allClasses
     * @param listener
     */
    private void rememberObject(JavaObject jo, long objAddress, Set<JavaClass> allClasses, IProgressListener listener)
    {

        // Always add object; the type can be guessed later and the object might
        // be something important like a thread or class loader
        indexToAddress.add(objAddress);
        // debugPrint("adding object at "+format(objAddress));

        try
        {
            JavaClass cls = jo.getJavaClass();
            // Check if class is not in class loader list - some array classes
            // are this way and the primitive types
            while (cls.isArray())
            {
                if (!allClasses.contains(cls))
                {
                    if (debugInfo) debugPrint("Adding extra array class " + getClassName(cls, listener)); //$NON-NLS-1$
                    allClasses.add(cls);
                }
                cls = cls.getComponentType();
            }
            if (!allClasses.contains(cls))
            {
                if (debugInfo) debugPrint("Adding extra class or component class " + getClassName(cls, listener)); //$NON-NLS-1$
                allClasses.add(cls);
            }
        }
        catch (CorruptDataException e)
        {
            if (msgNclassForObject-- > 0) listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_ProblemFindingClassesForObject, format(objAddress)), e);
        }
    }

    /**
     * Deal with a Java object. Build outbound references Set. Build class of
     * object Map. Build array size.
     * 
     * @param jo
     * @param pointerSize
     * @param bootLoaderAddress
     * @param loaders
     * @param jlc
     * @param clsJavaClass
     * @param refd
     * @param ic2
     * @param listener
     * @throws IOException
     */
    private void processHeapObject(JavaObject jo, int pointerSize, long bootLoaderAddress,
                    HashMap<JavaObject, JavaClassLoader> loaders, ClassImpl jlc, JavaClass clsJavaClass,
                    boolean[] refd, IndexWriter.IntIndexCollectorUncompressed ic2, IProgressListener listener)
                    throws IOException
    {
        long objAddr = jo.getID().getAddress();
        objAddr = fixBootLoaderAddress(bootLoaderAddress, objAddr);
        int objId = indexToAddress.reverse(objAddr);

        if (objId < 0)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(Messages.DTFJIndexBuilder_SkippingObject,
                            format(objAddr)), null);
            return;
        }

        if (idToClass.get(objId) != null)
        {
            // Class objects are dealt with elsewhere
            // debugPrint("Skipping class "+idToClass.get(objId).getName());
            return;
        }

        int clsId = -1;
        JavaClass type = null;
        long clsAddr = 0;
        try
        {
            type = jo.getJavaClass();
            clsAddr = getClassAddress(type, listener);

            clsId = indexToAddress.reverse(clsAddr);

            if (clsId >= 0)
            {
                if (verbose)
                    debugPrint("found object " + objId + " " + getClassName(type, listener) + " at " + format(objAddr) + " clsId " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                    + clsId);
            }
            else
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemGettingClassIDType, format(objAddr), getClassName(type, listener),
                                format(clsAddr)), null);
            }
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_ProblemGettingClassID, format(objAddr)), e);
        }

        if (clsId < 0)
        {
            // Make sure the object has a class e.g. java.lang.Object, even if
            // it is wrong!
            clsId = jlc.getSuperClassId();
            clsAddr = jlc.getSuperClassAddress();
            if (clsAddr == 0)
            {
                // Even more corrupt - no Object!
                clsId = jlc.getObjectId();
                clsAddr = jlc.getObjectAddress();
            }
            if (loaders.get(jo) != null)
            {
                // It is a loader, so try to make it the type of a class loader
                ClassImpl cls = findJavaLangClassloader(listener);
                if (cls != null)
                {
                    {
                        clsId = cls.getObjectId();
                        clsAddr = cls.getObjectAddress();
                    }
                }
            }
            // Leave type as null so we skip processing object fields/array
            // elements
        }

        // debugPrint("set objectID "+objId+" at address "+format(objAddr)+"
        // classID "+clsId+" "+format(clsAddr));
        objectToClass.set(objId, clsId);

        // Add object count/size to the class
        ClassImpl cls = idToClass.get(clsId);
        try
        {
            if (cls != null)
            {
                if (cls == jlc)
                {
                    debugPrint("Found class as object at " + format(objAddr)); //$NON-NLS-1$
                }
                int size = getObjectSize(jo, pointerSize);
                cls.addInstance(size);
                if (cls.isArrayType())
                {
                    cls.setHeapSizePerInstance(pointerSize);
                    int arrayLen = jo.getArraySize();
                    // Bytes or elements??
                    arrayLen = getObjectSize(jo, pointerSize);
                    ic2.set(objId, arrayLen);
                    // For calculating purge sizes
                    objectToSize.set(objId, arrayLen);
                    // debugPrint("array "+arrayLen+" "+jo.getArraySize());
                }
                else
                {
                    cls.setHeapSizePerInstance(size);
                    // For calculating purge sizes
                    objectToSize.set(objId, size);
                }
            }
            else
            {
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemGettingObjectClass, format(objAddr)), null);
            }
        }
        catch (CorruptDataException e)
        {
            if (msgNobjectSize-- > 0) listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_ProblemGettingObjectSize, format(objAddr)), e);
            // Try to cope with bad sizes - at least register an instance of
            // this class
            cls.addInstance(0);
            if (cls.getHeapSizePerInstance() == -1)
                cls.setHeapSizePerInstance(0);
        }

        // To accumulate the outbound refs
        ArrayLong aa = new ArrayLong();

        // Add a reference to the class
        aa.add(clsAddr);

        // Is the object a class loader?
        if (loaders.containsKey(jo))
        {
            addLoaderClasses(objId, aa);
        }

        if (type != null)
        {
            try
            {
                // Array size
                if (jo.isArray())
                {
                    // get the size
                    int arrayLen = jo.getArraySize();
                    exploreArray(indexToAddress, bootLoaderAddress, idToClass, jo, type, aa, arrayLen, listener);
                }
                else
                {
                    exploreObject(indexToAddress, bootLoaderAddress, idToClass, jo, type, aa, false, listener);
                }
            }
            catch (CorruptDataException e)
            {
                if (msgNoutboundReferences-- > 0) listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemGettingOutboundReferences, format(objAddr)), e);
            }
        }
        else
        {
            debugPrint("Null type"); //$NON-NLS-1$
        }
        try
        {
            checkRefs(jo, Messages.DTFJIndexBuilder_CheckRefsObject, aa, clsJavaClass, bootLoaderAddress, listener);
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_ProblemCheckingOutboundReferences, format(objAddr)), e);
        }

        // The GC roots associated with a thread are outbound references for the
        // thread, not global roots
        addThreadRefs(objId, aa);
        addRefs(refd, aa);
        outRefs.log(indexToAddress, objId, aa);
    }

    private ClassImpl findJavaLangClassloader(IProgressListener listener)
    {
        for (Iterator<ClassImpl> i = idToClass.values(); i.hasNext();)
        {
            ClassImpl cls = i.next();
            if (cls != null)
            {
                if (cls.getName().equals(ClassImpl.JAVA_LANG_CLASSLOADER)) { return cls; }
            }
            else
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_NullClassImpl, i), null);
            }
        }
        return null;
    }

    private void scanJavaThread(JavaThread th, long threadAddress, int pointerSize,
                    HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thr, IProgressListener listener,
                    boolean scanUp, PrintWriter pw) throws CorruptDataException
    {
        if (pw != null) 
        {
            printThreadStack(pw, th);
        }
        int frameId = 0;
        Set<Long> searched = new HashSet<Long>();
        // Find the first address
        long veryFirstAddr = 0;
        // The base pointer appears to be the last address of the frame, not the
        // first
        long prevAddr = 0;
        // We need to look ahead to get the frame size
        Object nextFrame = null;
        for (Iterator ii = th.getStackFrames(); nextFrame != null || ii.hasNext(); ++frameId)
        {
            // Use the lookahead frame if available
            Object next2;
            if (nextFrame != null)
            {
                next2 = nextFrame;
                nextFrame = null;
            }
            else
            {
                next2 = ii.next();
            }
            if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingJavaStackFrames, th))
                continue;
            JavaStackFrame jf = (JavaStackFrame) next2;
            if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
            Set<Long> searchedInFrame = new LinkedHashSet<Long>();
            long address = 0;
            long searchSize = JAVA_STACK_FRAME_SIZE;
            try
            {
                ImagePointer ip = getAlignedAddress(jf.getBasePointer(), pointerSize);
                address = ip.getAddress();
                if (scanUp)
                {
                    // Check the next frame to limit the current frame size
                    if (ii.hasNext())
                    {
                        nextFrame = ii.next();
                        if (!isCorruptData(nextFrame, listener,
                                        Messages.DTFJIndexBuilder_CorruptDataReadingJavaStackFrames, th))
                        {
                            JavaStackFrame jf2 = (JavaStackFrame) nextFrame;
                            try
                            {
                                ImagePointer ip2 = getAlignedAddress(jf2.getBasePointer(), pointerSize);
                                long address2 = ip2.getAddress();
                                long s2 = address2 - address;
                                if (s2 > 0 && s2 < searchSize)
                                {
                                    searchSize = s2;
                                }
                            }
                            catch (CorruptDataException e)
                            {
                                // Ignore for the moment - we'll find it again
                                // next time.
                            }
                        }
                    }
                }
                else
                {
                    // Check the previous frame to limit the current frame size
                    if (prevAddr == 0)
                    {
                        prevAddr = getJavaStackBase(th, address);
                    }
                    long s2 = address - prevAddr;
                    prevAddr = address;
                    if (s2 > 0 && s2 < searchSize)
                    {
                        searchSize = s2;
                    }
                    // Go backwards from ip so that we search the known good
                    // addresses first
                    searchSize = -searchSize;
                }
                if (veryFirstAddr == 0)
                {
                    veryFirstAddr = Math.min(address, address + searchSize);
                }
                if (debugInfo) debugPrint("Frame " + jf.getLocation().getMethod().getName()); //$NON-NLS-1$
                searchFrame(pointerSize, threadAddress, thr, ip, searchSize, GCRootInfo.Type.JAVA_LOCAL, gcRoot,
                                searchedInFrame, null);
            }
            catch (MemoryAccessException e)
            {
                // We don't know the size of the frame, so could go beyond the
                // end and get an error
                try
                {
                    JavaMethod jm = jf.getLocation().getMethod();
                    String className = jm.getDeclaringClass().getName();
                    String methodName = jm.getName();
                    String modifiers = getModifiers(jm, listener);
                    String sig = jm.getSignature();
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_PossibleProblemReadingJavaStackFramesMethod, frameId,
                                    format(address), searchSize, modifiers, className, methodName, sig,
                                    format(threadAddress)), e);
                }
                catch (DataUnavailable e2)
                {
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_PossibleProblemReadingJavaStackFrames, frameId,
                                    format(address), searchSize, format(threadAddress)), e);
                }
                catch (CorruptDataException e2)
                {
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_PossibleProblemReadingJavaStackFrames, frameId,
                                    format(address), searchSize, format(threadAddress)), e);
                }
            }
            catch (CorruptDataException e)
            {
                try
                {
                    JavaMethod jm = jf.getLocation().getMethod();
                    String className = jm.getDeclaringClass().getName();
                    String methodName = jm.getName();
                    String modifiers = getModifiers(jm, listener);
                    String sig;
                    try
                    {
                        sig = jm.getSignature();
                    }
                    catch (CorruptDataException e2)
                    {
                        sig = "()"; //$NON-NLS-1$
                    }
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingJavaStackFramesMethod, frameId,
                                    format(address), searchSize, modifiers, className, methodName, sig,
                                    format(threadAddress)), e);
                }
                catch (DataUnavailable e2)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingJavaStackFrames, frameId, format(address),
                                    searchSize, format(threadAddress)), e);
                }
                catch (CorruptDataException e2)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingJavaStackFrames, frameId, format(address),
                                    searchSize, format(threadAddress)), e);
                }
            }
            // Add all the searched locations in this frame to the master list
            searched.addAll(searchedInFrame);            
            if (pw != null)
            {
            	// Indicate the local variables associated with this frame
                for (long addr : searchedInFrame)
                {
                    try
                    {
                        long target = jf.getBasePointer().getAddressSpace().getPointer(addr).getPointerAt(0)
                                        .getAddress();
                        printLocal(pw, target, frameId);
                     }
                    catch (MemoryAccessException e)
                    {}
                    catch (CorruptDataException e)
                    {}
                }
            }            
            // Mark the classes of methods as referenced by the thread
            try
            {
                JavaMethod jm = jf.getLocation().getMethod();
                JavaClass cls = jm.getDeclaringClass();
                long clsAddress = getClassAddress(cls, listener);
                int clsId = indexToAddress.reverse(clsAddress);
                if (clsId >= 0)
                {
                    // Mark the class
                    HashMapIntObject<List<XGCRootInfo>> thr1 = thr.get(indexToAddress.reverse(threadAddress));
                    if (thr1 != null)
                    {
                        // Add it to the thread roots
                        addRoot(thr1, clsAddress, threadAddress, GCRootInfo.Type.JAVA_LOCAL);
                        // Add it to the global GC roots
                        if (!useThreadRefsNotRoots)
                            addRoot(gcRoot, clsAddress, threadAddress, GCRootInfo.Type.JAVA_LOCAL);
                    }
                    else
                    {
                        // No thread information so make a global root
                        addRoot(gcRoot, clsAddress, threadAddress, GCRootInfo.Type.JAVA_LOCAL);
                    }
                }
            }
            catch (DataUnavailable e2)
            {}
            catch (CorruptDataException e2)
            {}

        }
        if (pw != null)
        {
            pw.println();
        }
        for (Iterator ii = th.getStackSections(); ii.hasNext();)
        {
            Object next2 = ii.next();
            if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingJavaStackSections, th))
                continue;
            ImageSection is = (ImageSection) next2;
            if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
            ImagePointer ip = is.getBaseAddress();
            long size = is.getSize();
            try
            {
                debugPrint("Java stack section"); //$NON-NLS-1$
                if (size <= JAVA_STACK_SECTION_MAX_SIZE)
                {
                    searchFrame(pointerSize, threadAddress, thr, ip, size, GCRootInfo.Type.JAVA_LOCAL, gcRoot, null,
                                    searched);
                }
                else
                {
                    // Giant frame, so just search the top and the bottom rather
                    // than 500MB!
                    long size2 = size;
                    size = JAVA_STACK_SECTION_MAX_SIZE / 2;
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_HugeJavaStackSection, format(ip.getAddress()), size2,
                                    format(threadAddress), size), null);
                    searchFrame(pointerSize, threadAddress, thr, ip, size, GCRootInfo.Type.JAVA_LOCAL, gcRoot, null,
                                    searched);
                    ip = ip.add(size2 - size);
                    searchFrame(pointerSize, threadAddress, thr, ip, size, GCRootInfo.Type.JAVA_LOCAL, gcRoot, null,
                                    searched);
                }
            }
            catch (MemoryAccessException e)
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemReadingJavaStackSection, format(ip.getAddress()),
                                size, format(threadAddress)), e);
            }
            catch (CorruptDataException e)
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemReadingJavaStackSection, format(ip.getAddress()),
                                size, format(threadAddress)), e);
            }
        }
    }

    /**
     * Find the lowest address of the stack section which contains the given
     * address
     * 
     * @param th
     *            The thread.
     * @param addr
     *            The address in question.
     * @return The lowest address in the section containing the address.
     */
    private long getJavaStackBase(JavaThread th, long addr)
    {
        for (Iterator ii = th.getStackSections(); ii.hasNext();)
        {
            Object next2 = ii.next();
            if (next2 instanceof CorruptData)
                continue;
            ImageSection is = (ImageSection) next2;
            long base = is.getBaseAddress().getAddress();
            if (base <= addr && addr < base + is.getSize()) { return base; }
        }
        return 0;
    }

    private void scanImageThread(JavaThread th, ImageThread it, long threadAddress, int pointerSize,
                    HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thr, IProgressListener listener)
                    throws CorruptDataException
    {
        try
        {
            int frameId = 0;
            // We need to look ahead to get the frame size
            Object nextFrame = null;
            for (Iterator ii = it.getStackFrames(); nextFrame != null || ii.hasNext(); ++frameId)
            {
                // Use the lookahead frame if available
                Object next2;
                if (nextFrame != null)
                {
                    next2 = nextFrame;
                    nextFrame = null;
                }
                else
                {
                    next2 = ii.next();
                }
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingNativeStackFrames, th))
                    continue;
                ImageStackFrame jf = (ImageStackFrame) next2;
                if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
                ImagePointer ip = jf.getBasePointer();
                long searchSize = NATIVE_STACK_FRAME_SIZE;
                // Check the next frame to limit the current frame size
                if (ii.hasNext())
                {
                    nextFrame = ii.next();
                    if (!isCorruptData(nextFrame, listener,
                                    Messages.DTFJIndexBuilder_CorruptDataReadingNativeStackFrames, th))
                    {
                        ImageStackFrame jf2 = (ImageStackFrame) nextFrame;
                        try
                        {
                            ImagePointer ip2 = jf2.getBasePointer();
                            long s2 = ip2.getAddress() - ip.getAddress();
                            if (s2 > 0 && s2 < searchSize)
                            {
                                searchSize = s2;
                            }
                        }
                        catch (CorruptDataException e)
                        {
                            // Ignore for the moment - we'll find it again next
                            // time
                        }
                    }
                }
                try
                {
                    debugPrint("native stack frame"); //$NON-NLS-1$
                    searchFrame(pointerSize, threadAddress, thr, ip, searchSize, GCRootInfo.Type.NATIVE_STACK, gcRoot,
                                    null, null);
                }
                catch (MemoryAccessException e)
                {
                    // We don't know the size of the frame, so could go beyond
                    // the end and get an error
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_PossibleProblemReadingNativeStackFrame, frameId,
                                    format(ip.getAddress()), searchSize, format(threadAddress)), e);
                }
                catch (CorruptDataException e)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingNativeStackFrame, frameId, format(ip
                                                    .getAddress()), searchSize, format(threadAddress)), e);
                }
            }
        }
        catch (DataUnavailable e)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_NativeStackFrameNotFound, format(threadAddress)), e);
        }
        for (Iterator ii = it.getStackSections(); ii.hasNext();)
        {
            Object next2 = ii.next();
            if (isCorruptData(next2, listener,
                            Messages.DTFJIndexBuilder_DTFJIndexBuilder_CorruptDataReadingNativeStackSection, th))
                continue;
            ImageSection is = (ImageSection) next2;
            ImagePointer ip = is.getBaseAddress();
            long size = is.getSize();
            try
            {
                debugPrint("native stack section"); //$NON-NLS-1$
                if (size <= NATIVE_STACK_SECTION_MAX_SIZE)
                {
                    searchFrame(pointerSize, threadAddress, thr, ip, size, GCRootInfo.Type.NATIVE_STACK, gcRoot, null,
                                    null);
                }
                else
                {
                    // Giant frame, so just search the top and the bottom rather
                    // than 500MB!
                    long size2 = size;
                    size = NATIVE_STACK_SECTION_MAX_SIZE / 2;
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_HugeNativeStackSection, format(ip.getAddress()), size2,
                                    format(threadAddress), size), null);
                    searchFrame(pointerSize, threadAddress, thr, ip, size, GCRootInfo.Type.NATIVE_STACK, gcRoot, null,
                                    null);
                    ip = ip.add(size2 - size);
                    searchFrame(pointerSize, threadAddress, thr, ip, size, GCRootInfo.Type.NATIVE_STACK, gcRoot, null,
                                    null);
                }
            }
            catch (MemoryAccessException e)
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemReadingNativeStackSection, format(ip.getAddress()),
                                size, format(threadAddress)), e);
            }
            catch (CorruptDataException e)
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemReadingNativeStackSection, format(ip.getAddress()),
                                size, format(threadAddress)), e);
            }
        }
    }

    /**
     * The runtime version of MAT might not know this root type
     * 
     * @param rootType
     * @return rootType or UNKNOWN
     */
    private int newRootType(int rootType)
    {
        return GCRootInfo.getTypeAsString(rootType) != null ? rootType : GCRootInfo.Type.UNKNOWN;
    }

    /**
     * Add a root to the list of roots
     * 
     * @param r
     *            The reference to the object
     * @param thread
     *            Thread thread that referred to this object, or null
     * @param gcRoot2
     *            Where to store the global roots
     * @param threadRoots2
     *            Where to store the thread roots
     * @param pointerSize
     *            size of pointers in bytes
     * @param listener
     */
    private void processRoot(JavaReference r, JavaThread thread, HashMapIntObject<List<XGCRootInfo>> gcRoot2,
                    HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> threadRoots2, int pointerSize,
                    IProgressListener listener)
    {
        debugPrint("Process root " + r); //$NON-NLS-1$
        int type = 0;
        boolean threadRoot = false;
        int rootType = JavaReference.HEAP_ROOT_UNKNOWN;
        try
        {
            rootType = r.getRootType();
            switch (rootType)
            {
                case JavaReference.HEAP_ROOT_JNI_GLOBAL:
                    type = GCRootInfo.Type.NATIVE_STATIC;
                    break;
                case JavaReference.HEAP_ROOT_JNI_LOCAL:
                    type = GCRootInfo.Type.NATIVE_STACK;
                    type = GCRootInfo.Type.NATIVE_LOCAL;
                    threadRoot = true;
                    break;
                case JavaReference.HEAP_ROOT_MONITOR:
                    type = GCRootInfo.Type.BUSY_MONITOR;
                    threadRoot = true;
                    break;
                case JavaReference.HEAP_ROOT_STACK_LOCAL:
                    type = GCRootInfo.Type.JAVA_LOCAL;
                    threadRoot = true;
                    break;
                case JavaReference.HEAP_ROOT_SYSTEM_CLASS:
                    type = GCRootInfo.Type.SYSTEM_CLASS;
                    if (!useSystemClassRoots) { return; // Ignore system classes
                    // for moment as should
                    // be found via
                    // bootclassloader
                    }
                    break;
                case JavaReference.HEAP_ROOT_THREAD:
                    type = GCRootInfo.Type.THREAD_BLOCK;
                    type = GCRootInfo.Type.THREAD_OBJ;
                    break;
                case JavaReference.HEAP_ROOT_OTHER:
                    debugPrint("Root type HEAP_ROOT_OTHER"); //$NON-NLS-1$
                    type = GCRootInfo.Type.UNKNOWN;
                    break;
                case JavaReference.HEAP_ROOT_UNKNOWN:
                    debugPrint("Root type HEAP_ROOT_UNKNOWN"); //$NON-NLS-1$
                    type = GCRootInfo.Type.UNKNOWN;
                    break;
                case JavaReference.HEAP_ROOT_FINALIZABLE_OBJ:
                    // The object is in the finalizer queue
                    type = GCRootInfo.Type.FINALIZABLE;
                    // No need to guess
                    foundFinalizableGCRoots = true;
                    break;
                case JavaReference.HEAP_ROOT_UNFINALIZED_OBJ:
                    // The object will in the end need to be finalized, but is
                    // currently in use
                    type = GCRootInfo.Type.UNFINALIZED;
                    break;
                case JavaReference.HEAP_ROOT_CLASSLOADER:
                    type = GCRootInfo.Type.SYSTEM_CLASS;
                    if (!useSystemClassRoots)
                    {
                        // Ignore class loaders as will be found via instances
                        // of a class.
                        // E.g. Thread -> class java.lang.Thread -> bootstrap
                        // loader
                        return;
                    }
                    break;
                case JavaReference.HEAP_ROOT_STRINGTABLE:
                    type = GCRootInfo.Type.UNKNOWN;
                    break;
                default:
                    debugPrint("Unknown root type " + rootType); //$NON-NLS-1$
                    type = GCRootInfo.Type.UNKNOWN;
                    break;
            }
        }
        catch (CorruptDataException e)
        {
            type = GCRootInfo.Type.UNKNOWN;
            listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_UnableToFindTypeOfRoot, e);
        }
        int reach = JavaReference.REACHABILITY_UNKNOWN;
        try
        {
            reach = r.getReachability();
            switch (reach)
            {
                default:
                case JavaReference.REACHABILITY_UNKNOWN:
                case JavaReference.REACHABILITY_STRONG:
                    break;
                case JavaReference.REACHABILITY_WEAK:
                case JavaReference.REACHABILITY_SOFT:
                case JavaReference.REACHABILITY_PHANTOM:
                    if (skipWeakRoots)
                        return;
                    break;
            }
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_UnableToFindReachabilityOfRoot, e);
        }
        int refType = JavaReference.REFERENCE_UNKNOWN;
        try
        {
            refType = r.getReferenceType();
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_UnableToFindReferenceTypeOfRoot, e);
        }
        try
        {
            long target = 0;

            Object o = r.getTarget();
            if (o instanceof JavaObject)
            {
                JavaObject jo = (JavaObject) o;
                target = jo.getID().getAddress();
            }
            else if (o instanceof JavaClass)
            {
                JavaClass jc = (JavaClass) o;
                target = getClassAddress(jc, listener);
            }
            else
            {
                // Unknown root target, so ignore
                if (o != null)
                {
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnableToFindTargetOfRoot, o, o.getClass()), null);
                    debugPrint("Unexpected root type " + o.getClass()); //$NON-NLS-1$
                }
                else
                {
                    listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_NullTargetOfRoot, null);
                    debugPrint("Unexpected null root target"); //$NON-NLS-1$
                }
                return;
            }

            long source = target;
            try
            {
                Object so = r.getSource();
                if (so instanceof JavaObject)
                {
                    JavaObject jo = (JavaObject) so;
                    source = jo.getID().getAddress();
                }
                else if (so instanceof JavaClass)
                {
                    JavaClass jc = (JavaClass) so;
                    source = getClassAddress(jc, listener);
                }
                else if (so instanceof JavaStackFrame)
                {
                    JavaStackFrame js = (JavaStackFrame) so;
                    // Thread is supplied
                    if (thread != null)
                    {
                        JavaObject threadObject = thread.getObject();
                        if (threadObject != null)
                            source = threadObject.getID().getAddress();
                    }
                }
                else if (so instanceof JavaThread)
                {
                    // Not expected, but sov DTFJ returns this
                    JavaObject threadObject = ((JavaThread) so).getObject();
                    if (threadObject != null)
                        source = threadObject.getID().getAddress();
                    // Sov DTFJ has curious types
                    String desc = r.getDescription();
                    if (desc.startsWith("stack") || desc.startsWith("Register")) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        // These roots are not thread
                        type = GCRootInfo.Type.NATIVE_STACK;
                        threadRoot = true;
                    }
                }
                else if (so instanceof JavaRuntime)
                {
                    // Not expected, but J9 DTFJ returns this
                    debugPrint("Unexpected source " + so); //$NON-NLS-1$
                }
                else if (so == null)
                {
                    // Unknown
                }
                else
                {
                    debugPrint("Unexpected source " + so); //$NON-NLS-1$
                }
            }
            catch (CorruptDataException e)
            {
                listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                Messages.DTFJIndexBuilder_UnableToFindSourceOfRoot, format(target)), e);
            }
            catch (DataUnavailable e)
            {
                listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                Messages.DTFJIndexBuilder_UnableToFindSourceOfRoot, format(target)), e);
            }
            int targetId = indexToAddress.reverse(target);
            // Only used for missedRoots
            String desc = targetId + " " + format(target) + " " + format(source) + " " + rootType + " " + refType + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                            + reach + " " + r.getDescription(); //$NON-NLS-1$
            if (targetId < 0)
            {
                String desc2 = ""; //$NON-NLS-1$
                Exception e1 = null;
                try
                {
                    if (o instanceof JavaObject)
                    {
                        JavaObject jo = (JavaObject) o;
                        desc2 = getClassName(jo.getJavaClass(), listener);
                    }
                    else if (o instanceof JavaClass)
                    {
                        JavaClass jc = (JavaClass) o;
                        desc2 = getClassName(jc, listener);
                    }
                    else
                    {
                        // Should never occur
                        desc2 = desc2 + o;
                    }
                }
                catch (CorruptDataException e)
                {
                    // Ignore exception as just for logging
                    e1 = e;
                }
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_UnableToFindRoot, format(target), desc2, format(source),
                                rootType, r.getDescription()), e1);
                return;
            }
            if (newRootType(type) == GCRootInfo.Type.UNKNOWN)
            {
                String desc2 = ""; //$NON-NLS-1$
                Exception e1 = null;
                try
                {
                    if (o instanceof JavaObject)
                    {
                        JavaObject jo = (JavaObject) o;
                        desc2 = getClassName(jo.getJavaClass(), listener);
                    }
                    else if (o instanceof JavaClass)
                    {
                        JavaClass jc = (JavaClass) o;
                        desc2 = getClassName(jc, listener);
                    }
                    else
                    {
                        // Should never occur
                        desc2 = o.toString();
                    }
                }
                catch (CorruptDataException e)
                {
                    // Ignore exception as just for logging
                    e1 = e;
                }
                listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                Messages.DTFJIndexBuilder_MATRootTypeUnknown, type, format(target), desc2,
                                format(source), rootType, r.getDescription()), e1);
            }
            if (threadRoot)
            {
                int thrId = indexToAddress.reverse(source);
                if (thrId >= 0)
                {
                    HashMapIntObject<List<XGCRootInfo>> thr = threadRoots2.get(thrId);
                    if (thr == null)
                    {
                        // Build new list for the thread
                        thr = new HashMapIntObject<List<XGCRootInfo>>();
                        threadRoots2.put(thrId, thr);
                    }
                    addRoot(thr, target, source, type);
                    if (!useThreadRefsNotRoots)
                        addRoot(gcRoot2, target, source, type);
                }
                else
                {
                    addRoot(gcRoot2, target, source, type);
                }
            }
            else
            {
                addRoot(gcRoot2, target, source, type);
            }
            int tgt = targetId;
            int src = indexToAddress.reverse(source);
            if (src < 0)
            {
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_UnableToFindSourceID, format(target), format(source), r
                                                .getDescription()), null);
            }
            missedRoots.put(Integer.valueOf(tgt), desc);
        }
        catch (DataUnavailable e)
        {
            listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_ProblemGettingRoots, e);
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_ProblemGettingRoots, e);
        }
    }

    private int getObjectSize(JavaObject jo, int pointerSize) throws CorruptDataException
    {
        // DTFJ size includes any link field, so just round to 8 bytes
        long s = (jo.getSize() + 7) & ~7L;
        return (int) s;
    }

    /**
     * Get an aligned version of a pointer.
     * 
     * @param p
     *            The original pointer.
     * @param pointerSize
     *            The size to align to.
     * @return The aligned pointer.
     */
    static ImagePointer getAlignedAddress(ImagePointer p, int pointerSize)
    {
        if (p == null)
            return p;
        long addr = p.getAddress();
        if (pointerSize == 8)
        {
            addr &= 7L;
        }
        else
        {
            addr &= 3L;
        }
        return p.add(-addr);
    }

    /**
     * @param idToClass2
     */
    private HashMapIntObject<ClassImpl> copy(HashMapIntObject<ClassImpl> idToClass1)
    {
        HashMapIntObject<ClassImpl> idToClass2 = new HashMapIntObject<ClassImpl>(idToClass1.size());
        for (IteratorInt ii = idToClass1.keys(); ii.hasNext();)
        {
            int i = ii.next();
            idToClass2.put(i, idToClass1.get(i));
        }
        return idToClass2;
    }

    /**
     * @param objectToClass2
     */
    private IndexWriter.IntIndexCollector copy(IndexWriter.IntIndexCollector objectToClass1, int bits)
    {
        IndexWriter.IntIndexCollector objectToClass2 = new IndexWriter.IntIndexCollector(objectToClass1.size(), bits);
        for (int i = 0; i < objectToClass1.size(); ++i)
        {
            int j = objectToClass1.get(i);
            objectToClass2.set(i, j);
        }
        return objectToClass2;
    }

    /**
     * Build a cache of classes loaded by each loader
     * @return
     */
    private HashMapIntObject<ArrayLong> initLoaderClassesCache()
    {
        HashMapIntObject<ArrayLong> cache = new HashMapIntObject<ArrayLong>();
        for (Iterator<ClassImpl> i = idToClass.values(); i.hasNext();)
        {
            ClassImpl ci = i.next();
            int load = ci.getClassLoaderId();
            if (!cache.containsKey(load))
                cache.put(load, new ArrayLong());
            ArrayLong classes = cache.get(load);
            classes.add(ci.getObjectAddress());
        }
        return cache;
    }

    /**
     * @param objId
     * @param aa
     */
    private void addLoaderClasses(int objId, ArrayLong aa)
    {
        debugPrint("Found loader " + objId + " at address " + format(indexToAddress.get(objId)) + " size=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + idToClass.size());
        // Add all the classes loaded by it as references
        ArrayLong classes = loaderClassCache.get(objId);
        if (classes != null)
        {
            aa.addAll(classes);
        }
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     * @return
     */
    private static boolean isCorruptData(Object next, IProgressListener listener, String msg)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            if (listener != null)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(msg, formattedCorruptDataAddress(d), d
                                .toString()), new CorruptDataException(d));
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     * @param detail
     *            - some more information about the source for the iterator
     * @param addr
     *            - the address of the source for the iterator
     * @return
     */
    private boolean isCorruptData(Object next, IProgressListener listener, String msg, String detail, long addr)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            if (listener != null)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(msg, formattedCorruptDataAddress(d), d
                                .toString(), detail, format(addr)), new CorruptDataException(d));
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Format the data address of the corruption. Avoid problems if no address
     * is available.
     * 
     * @param d
     * @return
     */
    private static String formattedCorruptDataAddress(CorruptData d)
    {
        ImagePointer ip = d.getAddress();
        if (ip != null)
        {
            return format(d.getAddress().getAddress());
        }
        else
        {
            // No address in the corrupt data Translate?
            return "null"; //$NON-NLS-1$
        }
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     * @return
     */
    private static boolean isCorruptData(Object next, IProgressListener listener, String msg, JavaRuntime detail)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            long addr;
            try
            {
                addr = detail.getJavaVM().getAddress();
            }
            catch (CorruptDataException e)
            {
                addr = 0;
            }
            logCorruptData(listener, msg, d, addr);
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     * @return
     */
    private static boolean isCorruptData(Object next, IProgressListener listener, String msg, JavaClassLoader detail)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            long addr;
            try
            {
                JavaObject ldr = detail.getObject();
                if (ldr != null)
                {
                    addr = ldr.getID().getAddress();
                }
                else
                {
                    addr = 0;
                }
            }
            catch (CorruptDataException e)
            {
                addr = 0;
            }
            logCorruptData(listener, msg, d, addr);
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     *            With corrupt data address {0} corrupt data {1} class name {2}
     *            class address {3}
     * @return
     */
    private boolean isCorruptData(Object next, IProgressListener listener, String msg, JavaClass detail)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            long addr = getClassAddress(detail, listener);
            String name;
            try
            {
                name = detail.getName();
            }
            catch (CorruptDataException e)
            {
                name = e.toString();
            }
            if (listener != null)
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(msg, formattedCorruptDataAddress(d), d
                                .toString(), name, format(addr)), new CorruptDataException(d));
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     *            With corrupt data address {0} corrupt data {1} class name {2}
     *            class address {3} modifiers {4} class name {5} method name {6}
     *            method signature {7}
     * @return
     */
    private boolean isCorruptData(Object next, IProgressListener listener, String msg, JavaClass jc, JavaMethod detail)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            String clsName;
            String methName;
            String methSig;
            try
            {
                clsName = jc != null ? jc.getName() : ""; //$NON-NLS-1$
            }
            catch (CorruptDataException e)
            {
                clsName = e.toString();
            }
            try
            {
                methName = detail.getName();
            }
            catch (CorruptDataException e)
            {
                methName = e.toString();
            }
            try
            {
                methSig = detail.getSignature();
            }
            catch (CorruptDataException e)
            {
                methSig = e.toString();
            }
            long addr = jc != null ? getClassAddress(jc, listener) : 0;
            String modifiers = getModifiers(detail, listener);
            if (listener != null)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(msg, formattedCorruptDataAddress(d), d
                                .toString(), clsName, format(addr), modifiers, clsName, methName, methSig),
                                new CorruptDataException(d));
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Get the modifiers for a method (public/private etc.)
     * 
     * @param detail
     * @param listener
     *            for logging error messages
     * @return A string representation of the modifiers.
     */
    private String getModifiers(JavaMethod detail, IProgressListener listener)
    {
        String modifiers;
        int mods;
        Exception e1 = null;
        try
        {
            // Remove unexpected modifiers - DTFJ defect?
            mods = detail.getModifiers();
        }
        catch (CorruptDataException e)
        {
            mods = 0;
            e1 = e;
        }

        final int expectedMods = (Modifier.ABSTRACT | Modifier.FINAL | Modifier.NATIVE | Modifier.PRIVATE
                        | Modifier.PROTECTED | Modifier.PUBLIC | Modifier.STATIC | Modifier.STRICT | Modifier.SYNCHRONIZED);
        int unexpectedMods = mods & ~expectedMods;
        if ((e1 != null || unexpectedMods != 0) && msgNunexpectedModifiers-- >= 0)
        {
            String m1 = Modifier.toString(unexpectedMods);
            String methName = ""; //$NON-NLS-1$
            String methClass = ""; //$NON-NLS-1$
            String sig = ""; //$NON-NLS-1$
            try
            {
                methName = detail.getName();
                methClass = detail.getDeclaringClass().getName();
            }
            catch (CorruptDataException e)
            {
                if (e1 == null)
                    e1 = e;
            }
            catch (DataUnavailable e)
            {
                if (e1 == null)
                    e1 = e;
            }
            try
            {
                sig = detail.getSignature();
            }
            catch (CorruptDataException e)
            {
                sig = "()"; //$NON-NLS-1$
                if (e1 == null)
                    e1 = e;
            }
            String mod = Modifier.toString(mods);
            listener.sendUserMessage(Severity_INFO, MessageFormat.format(Messages.DTFJIndexBuilder_UnexpectedModifiers,
                            format(unexpectedMods), m1, mod, methClass, methName, sig), e1);
        }
        modifiers = Modifier.toString(mods & expectedMods);
        return modifiers;
    }

    /**
     * Helper method to test whether object is corrupt and to log the corrupt
     * data
     * 
     * @param next
     * @param listener
     * @param msg
     *            address {0} corrupt data {1} thread name {2} thread address
     *            {3}
     * @return
     */
    private static boolean isCorruptData(Object next, IProgressListener listener, String msg, JavaThread detail)
    {
        if (next instanceof CorruptData)
        {
            CorruptData d = (CorruptData) next;
            long addr;
            String name;
            if (detail == null)
            {
                // Could be scanning a image thread without an Java thread
                addr = 0;
                name = ""; //$NON-NLS-1$
            }
            else
            {
                try
                {
                    JavaObject threadObject = detail.getObject();
                    // Thread object could be null if the thread is being
                    // attached
                    if (threadObject != null)
                    {
                        addr = threadObject.getID().getAddress();
                    }
                    else
                    {
                        addr = 0;
                    }
                }
                catch (CorruptDataException e)
                {
                    addr = 0;
                }
                try
                {
                    name = detail.getName();
                }
                catch (CorruptDataException e)
                {
                    name = e.toString();
                }
            }
            if (listener != null)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(msg, formattedCorruptDataAddress(d), d
                                .toString(), name, format(addr)), new CorruptDataException(d));
            return true;
        }
        else
        {
            return false;
        }
    }

    private static void logCorruptData(IProgressListener listener, String msg, CorruptData d, long addr)
    {
        if (listener != null)
            listener.sendUserMessage(Severity.ERROR, MessageFormat.format(msg, formattedCorruptDataAddress(d), d
                            .toString(), format(addr)), new CorruptDataException(d));
    }

    /**
     * Find a Java runtime from the image
     * 
     * @param image
     * @param listener
     * @return
     */
    static JavaRuntime getRuntime(Image image, IProgressListener listener) throws IOException
    {
        JavaRuntime run = null;
        int nAddr = 0;
        int nProc = 0;
        String lastAddr = ""; //$NON-NLS-1$
        String lastProc = ""; //$NON-NLS-1$
        for (Iterator i1 = image.getAddressSpaces(); i1.hasNext();)
        {
            Object next1 = i1.next();
            if (isCorruptData(next1, listener, Messages.DTFJIndexBuilder_CorruptDataReadingAddressSpaces))
                continue;
            ImageAddressSpace ias = (ImageAddressSpace) next1;
            ++nAddr;
            lastAddr = ias.toString();
            for (Iterator i2 = ias.getProcesses(); i2.hasNext();)
            {
                Object next2 = i2.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingProcesses))
                    continue;
                ImageProcess proc = (ImageProcess) next2;
                ++nProc;
                try
                {
                    lastProc = proc.getID();
                }
                catch (DataUnavailable e)
                {
                    if (listener != null)
                        listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_ErrorReadingProcessID, e);
                }
                catch (CorruptDataException e)
                {
                    if (listener != null)
                        listener.sendUserMessage(Severity.INFO, Messages.DTFJIndexBuilder_ErrorReadingProcessID, e);
                }
                for (Iterator i3 = proc.getRuntimes(); i3.hasNext();)
                {
                    Object next3 = i3.next();
                    if (isCorruptData(next3, listener, Messages.DTFJIndexBuilder_CorruptDataReadingRuntimes))
                        continue;
                    if (next3 instanceof JavaRuntime)
                    {
                        if (run == null)
                        {
                            run = (JavaRuntime) next3;
                        }
                        else
                        {
                            ManagedRuntime mr = (ManagedRuntime) next3;
                            Exception e1 = null;
                            String version = null;
                            try
                            {
                                version = mr.getVersion();
                            }
                            catch (CorruptDataException e)
                            {
                                e1 = e;
                            }
                            if (listener != null)
                                listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_IgnoringExtraJavaRuntime, version), e1);
                        }
                    }
                    else
                    {
                        ManagedRuntime mr = (ManagedRuntime) next3;
                        Exception e1 = null;
                        String version = null;
                        try
                        {
                            version = mr.getVersion();
                        }
                        catch (CorruptDataException e)
                        {
                            e1 = e;
                        }
                        if (listener != null)
                            listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_IgnoringManagedRuntime, version), e1);
                    }
                }
            }
        }
        if (run == null) { throw new IOException(MessageFormat.format(
                        Messages.DTFJIndexBuilder_UnableToFindJavaRuntime, nAddr, lastAddr, nProc, lastProc)); }
        return run;
    }

    /**
     * Find the pointer size for the runtime
     * 
     * @param run1
     *            The Java runtime
     * @param listener
     *            To indicate progress/errors
     * @return the pointer size in bytes
     */
    private int getPointerSize(JavaRuntime run1, IProgressListener listener)
    {
        int pointerSize = 0;
        try
        {
            ImageAddressSpace ias = getAddressSpace(run1);
            for (Iterator i2 = ias.getProcesses(); i2.hasNext();)
            {
                Object next2 = i2.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingProcesses))
                    continue;
                ImageProcess proc = (ImageProcess) next2;
                for (Iterator i3 = proc.getRuntimes(); i3.hasNext();)
                {
                    Object next3 = i3.next();
                    if (isCorruptData(next3, listener, Messages.DTFJIndexBuilder_CorruptDataReadingRuntimes))
                        continue;
                    if (next3 instanceof JavaRuntime)
                    {
                        JavaRuntime run = (JavaRuntime) next3;
                        if (run1 == run)
                        {
                            // 31,32,64 bits to bytes conversion
                            pointerSize = (proc.getPointerSize() + 1) / 8;
                            return pointerSize;
                        }
                    }
                }
            }
        }
        catch (CorruptDataException e)
        {
            pointerSize = 4;
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_UnableToFindPointerSize, pointerSize), e);
        }
        return pointerSize;
    }

    /**
     * Try to get the address space from the runtime
     * 
     * @param run1
     * @return
     * @throws CorruptDataException
     */
    static ImageAddressSpace getAddressSpace(JavaRuntime run1) throws CorruptDataException
    {
        ImageAddressSpace ias;
        try
        {
            // Fails for Javacore and PHDs
            ias = run1.getJavaVM().getAddressSpace();
        }
        catch (CorruptDataException e)
        {
            for (Iterator i = run1.getJavaClassLoaders(); i.hasNext();)
            {
                Object next = i.next();
                if (isCorruptData(next, null, Messages.DTFJIndexBuilder_CorruptDataReadingClassLoaders1, run1))
                    continue;
                JavaClassLoader jcl = (JavaClassLoader) next;
                JavaObject loaderObject = jcl.getObject();
                if (loaderObject != null)
                {
                    ias = loaderObject.getID().getAddressSpace();
                    return ias;
                }
            }
            throw e;
        }
        return ias;
    }

    /**
     * @param obj
     * @param j
     * @param listener
     *            To indicate progress/errors
     */
    private void addRootForThreads(JavaObject obj, Iterator j, IProgressListener listener)
    {
        for (; j.hasNext();)
        {
            Object next2 = j.next();
            if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingThreadsFromMonitors))
                continue;
            JavaThread jt2 = (JavaThread) next2;
            addRootForThread(obj, jt2, listener);
        }
    }

    private void addRootForThread(JavaObject obj, JavaThread jt2, IProgressListener listener)
    {
        long objAddress = obj.getID().getAddress();
        if (jt2 != null)
        {
            try
            {
                JavaObject threadObject = jt2.getObject();
                if (threadObject != null)
                {
                    long thrd2 = threadObject.getID().getAddress();
                    int thrId = indexToAddress.reverse(thrd2);
                    if (thrId >= 0)
                    {
                        HashMapIntObject<List<XGCRootInfo>> thr = threadRoots.get(thrId);
                        if (thr != null)
                        {
                            addRoot(thr, objAddress, thrd2, GCRootInfo.Type.BUSY_MONITOR);
                            if (useThreadRefsNotRoots)
                                return;
                        }
                        else
                        {
                            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_ProblemFindingRootInformation, format(thrd2),
                                            format(objAddress)), null);
                        }
                    }
                    else
                    {
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ProblemFindingThread, format(thrd2),
                                        format(objAddress)), null);
                    }
                }
                else
                {
                    // Null thread object
                }
            }
            catch (CorruptDataException e)
            {
                listener
                                .sendUserMessage(Severity.WARNING,
                                                Messages.DTFJIndexBuilder_ProblemGettingThreadInformation, e);
            }
        }
        else
        {
            debugPrint("Null thread, so no thread specific root"); //$NON-NLS-1$
        }
        addRoot(gcRoot, objAddress, objAddress, GCRootInfo.Type.BUSY_MONITOR);
    }

    /**
     * Gets all the outbound references from an object via DTFJ Compares them to
     * the supplied refs Optionally replaces them
     * 
     * @param type
     * @param desc
     * @param aa
     * @param clsJavaLangClass
     *            Representation of java.lang.Class
     * @param bootLoaderAddress
     *            The MAT view of the address of the boot loader
     * @param listener
     *            To indicate progress/errors
     * @throws CorruptDataException
     */
    private void checkRefs(Object type, String desc, ArrayLong aa, JavaClass clsJavaLangClass, long bootLoaderAddress,
                    IProgressListener listener) throws CorruptDataException
    {

        if (!haveDTFJRefs)
            return;

        HashSet<Long> aaset = new LinkedHashSet<Long>();

        for (IteratorLong il = aa.iterator(); il.hasNext();)
        {
            aaset.add(Long.valueOf(il.next()));
        }

        HashMap<Long, String> objset = new LinkedHashMap<Long, String>();

        Iterator i2;
        boolean hasDTFJRefs = false;

        String name = ""; //$NON-NLS-1$
        long objAddr;
        if (type instanceof JavaClass)
        {
            JavaClass jc = (JavaClass) type;

            JavaClass clsOfCls;
            JavaObject clsObj;
            try
            {
                clsObj = jc.getObject();
            }
            catch (CorruptDataException e)
            {
                // This error will have already been logged
                clsObj = null;
            }
            if (clsObj != null)
            {
                clsOfCls = clsObj.getJavaClass();
            }
            else
            {
                // Sometime there is not an associated Java object
                clsOfCls = clsJavaLangClass;
            }

            name = getClassName(jc, listener);
            objAddr = getClassAddress(jc, listener);
            try
            {
                // Collect references as well from JavaObject representing the
                // class
                if (clsObj != null)
                {
                    // Must have a reference to java.lang.Class first in the
                    // list, normally obtained from the java.lang.Class Object
                    // objset.put(getClassAddress(clsOfCls, listener), "added
                    // java.lang.Class address"); // Doesn't
                    // reference class
                    i2 = clsObj.getReferences();
                    hasDTFJRefs |= i2.hasNext();
                    collectRefs(i2, objset, desc, name, objAddr, listener);
                }
                else
                {
                    // Must have a reference to java.lang.Class first in the
                    // list, so add one now
                    objset.put(getClassAddress(clsOfCls, listener), "added java.lang.Class address"); //$NON-NLS-1$
                }
                i2 = jc.getReferences();
            }
            catch (LinkageError e)
            {
                // If not implemented, then ignore
                return;
            }
            catch (NullPointerException e)
            {
                // Null Pointer exception from array classes because
                // of null class loader
                if (msgNarrayRefsNPE-- > 0)
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ExceptionGettingOutboundReferences, desc, name,
                                    format(objAddr)), e);
                return;
            }

            if (clsObj != null)
            {
                // Sov used to miss this
                if (false)
                    objset.put(clsObj.getID().getAddress(), "added JavaObject for JavaClass"); //$NON-NLS-1$
            }

            JavaClassLoader classLoader = getClassLoader(jc, listener);
            long loaderAddr = getLoaderAddress(classLoader, bootLoaderAddress);
            if (classLoader == null || classLoader.getObject() == null)
            {
                if (debugInfo) debugPrint("Null loader obj " + getClassName(jc, listener)); //$NON-NLS-1$
                // getReferences won't find this otherwise
                objset.put(loaderAddr, "added boot loader"); //$NON-NLS-1$
            }
            else
            {
                // Doesn't reference classloader
                if (false)
                    objset.put(loaderAddr, "added class loader"); //$NON-NLS-1$
            }

            JavaClass sup = getSuperclass(jc, listener);
            if (sup != null)
            {
                // Doesn't reference superclass
                if (false)
                    objset.put(getClassAddress(sup, listener), "added super class"); //$NON-NLS-1$
            }
        }
        else if (type instanceof JavaObject)
        {
            JavaObject jo = (JavaObject) type;
            objAddr = jo.getID().getAddress();
            JavaClass clsObj;
            try
            {
                clsObj = jo.getJavaClass();
                name = getClassName(clsObj, listener);
                if (clsObj.isArray())
                {
                    // Sov doesn't ref array class, instead it gives the element
                    // type.
                    objset.put(getClassAddress(clsObj, listener), "added array class address"); //$NON-NLS-1$
                }
                // Doesn't reference class
                if (false)
                    objset.put(getClassAddress(clsObj, listener), "added class address"); //$NON-NLS-1$
            }
            catch (CorruptDataException e)
            {
                // Class isn't available (e.g. corrupt heap), so add some
                // information based on what processHeap guessed
                int objId = indexToAddress.reverse(objAddr);
                int clsId = objectToClass.get(objId);
                ClassImpl cls = idToClass.get(clsId);
                if (cls != null)
                {
                    long classAddr = cls.getObjectAddress();
                    name = cls.getName();
                    objset.put(classAddr, "added dummy class address"); //$NON-NLS-1$
                }
            }
            // Classloader doesn't ref classes
            // superclass fields are missed
            // array objects return null refs
            try
            {
                i2 = jo.getReferences();
            }
            catch (LinkageError e)
            {
                // If not implemented, then ignore
                return;
            }
            catch (OutOfMemoryError e)
            {
                // OutOfMemoryError with large object array
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ErrorGettingOutboundReferences, desc, name, format(objAddr)),
                                e);
                return;
            }
        }
        else
        {
            // Null boot loader object
            return;
        }
        int objId = indexToAddress.reverse(objAddr);

		// If there are no DTFJ refs (e.g. javacore) then don't use DTFJ refs
        hasDTFJRefs |= i2.hasNext();
        collectRefs(i2, objset, desc, name, objAddr, listener);

        // debugPrint("Obj "+type+" "+aaset.size()+" "+objset.size());
        // for (IteratorLong il = aa.iterator(); il.hasNext(); ) {
        // debugPrint("A "+format(il.next()));
        // }
        HashSet<Long> temp = new HashSet<Long>(aaset);
        temp.removeAll(objset.keySet());
        for (Long l : temp)
        {
            int newObjId = indexToAddress.reverse(l);
            String clsInfo = objDesc(newObjId);
            if (msgNgetRefsMissing-- > 0)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_DTFJGetReferencesMissingID, newObjId, format(l), clsInfo,
                                desc, name, objId, format(objAddr)), null);
        }
        if (false && !temp.isEmpty())
        {
            debugPrint("All DTFJ references from " + desc + " " + name); //$NON-NLS-1$ //$NON-NLS-2$
            for (Long l : objset.keySet())
            {
                debugPrint(format(l) + " " + objset.get(l)); //$NON-NLS-1$
            }
        }
        temp = new HashSet<Long>(objset.keySet());
        temp.removeAll(aaset);
        for (Long l : temp)
        {
            int newObjId = indexToAddress.reverse(l);
            String clsInfo = objDesc(newObjId);
            // extra superclass references for objects
            if (msgNgetRefsExtra-- > 0)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_DTFJGetReferencesExtraID, newObjId, format(l), objset.get(l),
                                clsInfo, desc, name, objId, format(objAddr)), null);
            // Remove unknown references
            if (newObjId < 0)
                objset.remove(l);
        }

        long a2[] = new long[objset.size()];
        int i = 0;
        for (long l : objset.keySet())
        {
            a2[i++] = l;
        }

        if (false && aa.size() > 200)
        {
            debugPrint("aa1 " + aa.size()); //$NON-NLS-1$
            for (IteratorLong il = aa.iterator(); il.hasNext();)
            {
                debugPrint("A " + format(il.next())); //$NON-NLS-1$
            }
        }
        if (useDTFJRefs)
        {
            if (a2.length == 0 || !hasDTFJRefs)
            {
                // Sov has problems with objects of type [B, [C etc.
                if (!aa.isEmpty() && msgNgetRefsAllMissing-- > 0)
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_DTFJGetReferencesMissingAllReferences, name, objId,
                                    format(objAddr)), null);
            }
            else
            {
                aa.clear();
                aa.addAll(a2);
            }
        }
        if (false && aa.size() > 200)
        {
            debugPrint("aa1 " + aa.size()); //$NON-NLS-1$
            for (IteratorLong il = aa.iterator(); il.hasNext();)
            {
                debugPrint("A " + format(il.next())); //$NON-NLS-1$
            }
        }
    }

    /**
     * Describe the object at the given index
     * 
     * @param newObjId
     * @return
     */
    private String objDesc(int newObjId)
    {
        String clsInfo;
        if (newObjId >= 0)
        {
            ClassImpl classInfo = idToClass.get(newObjId);
            if (classInfo != null)
            {
                clsInfo = MessageFormat.format(Messages.DTFJIndexBuilder_ObjDescClass, classInfo.getName());
            }
            else
            {
                int clsId = objectToClass.get(newObjId);
                if (clsId >= 0 && clsId < indexToAddress.size())
                {
                    long clsAddr = indexToAddress.get(clsId);
                    classInfo = idToClass.get(clsId);
                    // If objectToClass has not yet been filled in for objects
                    // then this could be null
                    if (classInfo != null)
                    {
                        clsInfo = MessageFormat.format(Messages.DTFJIndexBuilder_ObjDescObjType, classInfo.getName(),
                                        format(clsAddr));
                    }
                    else
                    {
                        clsInfo = MessageFormat
                                        .format(Messages.DTFJIndexBuilder_ObjDescObjTypeAddress, format(clsAddr));
                    }
                }
                else
                {
                    clsInfo = ""; //$NON-NLS-1$
                }
            }
        }
        else
        {
            clsInfo = ""; //$NON-NLS-1$
        }
        return clsInfo;
    }

    /**
     * Collect all the outbound references from a JavaClass/JavaObject
     * 
     * @param i2
     *            Iterator to walk over the references
     * @param objset
     *            Where to put the references
     * @param desc
     *            Type of base object (Class, Object, Class loader etc.)
     * @param name
     *            Name of object
     * @param objAddr
     *            Its address
     * @param listener
     *            For displaying messages
     * @throws CorruptDataException
     */
    private void collectRefs(Iterator i2, HashMap<Long, String> objset, String desc, String name, long objAddr,
                    IProgressListener listener)
    {
        // Check the refs
        // Javacore reader gives null rather than an empty iterator
        if (i2 == null) { return; }
        for (; i2.hasNext();)
        {
            Object next3 = i2.next();
            if (isCorruptData(next3, listener, Messages.DTFJIndexBuilder_CorruptDataReadingReferences, name, objAddr))
                continue;
            JavaReference jr = (JavaReference) next3;
            long addr;
            try
            {
                Object target = jr.getTarget();
                if (jr.isClassReference())
                {
                    addr = getClassAddress((JavaClass) target, listener);
                }
                else if (jr.isObjectReference())
                {
                    addr = ((JavaObject) target).getID().getAddress();
                }
                else
                {
                    // neither of isClassReference and
                    // isObjectReference return true
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnexpectedReferenceType, jr.getDescription(), desc, name,
                                    format(objAddr)), null);
                    if (target == null)
                    {
                        // array objects return null refs
                        // null reference for classes without super
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_UnexpectedNullReferenceTarget, jr.getDescription(),
                                        desc, name, format(objAddr)), null);
                        continue;
                    }
                    else if (target instanceof JavaClass)
                    {
                        addr = getClassAddress((JavaClass) target, listener);
                    }
                    else if (target instanceof JavaObject)
                    {
                        addr = ((JavaObject) target).getID().getAddress();
                    }
                    else
                    {
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_UnexpectedReferenceTargetType, target, jr
                                                        .getDescription(), desc, name, format(objAddr)), null);
                        continue;
                    }
                }
                // Skip class references to itself via the class object (as
                // these are considered all part of the one class)
                if (!(addr == objAddr && (jr.getReferenceType() == JavaReference.REFERENCE_CLASS_OBJECT || jr
                                .getReferenceType() == JavaReference.REFERENCE_ASSOCIATED_CLASS)))
                {
                    objset.put(Long.valueOf(addr), jr.getDescription());
                }
            }
            catch (DataUnavailable e)
            {
                if (msgNgetRefsUnavailable-- > 0)
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnableToGetOutboundReference, jr.getDescription(), desc,
                                    name, format(objAddr)), e);
            }
            catch (CorruptDataException e)
            {
                if (msgNgetRefsCorrupt-- > 0)
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnableToGetOutboundReference, jr.getDescription(), desc,
                                    name, format(objAddr)), e);
            }
        }
    }

    /**
     * Add all the Java locals etc for the thread as outbound references
     * 
     * @param obj
     * @param aa
     */
    private void addThreadRefs(int obj, ArrayLong aa)
    {
        if (useThreadRefsNotRoots)
        {
            // The thread roots must be set up by this point
            HashMapIntObject<List<XGCRootInfo>> hm = threadRoots.get(obj);
            if (hm != null)
            {
                for (IteratorInt i = hm.keys(); i.hasNext();)
                {
                    int objId = i.next();
                    aa.add(indexToAddress.get(objId));
                }
            }
        }
    }

    /**
     * Remember objects which have been referred to Use to make sure every
     * object will be reachable
     * 
     * @param refd
     * @param ref
     */
    private void addRefs(boolean[] refd, ArrayLong ref)
    {
        for (IteratorLong il = ref.iterator(); il.hasNext();)
        {
            long ad = il.next();
            int id = indexToAddress.reverse(ad);
            // debugPrint("ref to "+id+" 0x"+format(ad));
            if (id >= 0)
            {
                refd[id] = true;
            }
        }
    }

    /**
     * @param type
     *            The JavaClass
     * @param listener
     *            For logging
     * @return the address of the Java Object representing this class
     */
    private long getClassAddress(final JavaClass type, IProgressListener listener)
    {
        JavaObject clsObject;
        Exception e1 = null;
        try
        {
            clsObject = type.getObject();
        }
        catch (CorruptDataException e)
        {
            // Ignore the error and proceed as though it was not available e.g.
            // javacore
            clsObject = null;
            e1 = e;
        }
        catch (IllegalArgumentException e)
        {
            // IllegalArgumentException if object address not found
            clsObject = null;
            e1 = e;
        }
        if (clsObject == null)
        {
            // use the class address if the object address is not available
            ImagePointer ip = type.getID();
            if (ip != null)
            {
                return ip.getAddress();
            }
            else
            {
                // This may be is a class which DTFJ built
                Long addr = dummyClassAddress.get(type);
                if (addr != null)
                {
                    // Return the address we have already used
                    return addr;
                }
                else
                {
                    // Build a unique dummy address
                    long clsAddr = nextClassAddress;
                    dummyClassAddress.put(type, clsAddr);
                    nextClassAddress += 8;
                    String clsName;
                    try
                    {
                        clsName = getClassName(type, listener);
                    }
                    catch (CorruptDataException e)
                    {
                        clsName = type.toString();
                    }
                    listener.sendUserMessage(Severity.INFO, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ClassHasNoAddress, clsName, format(clsAddr)), e1);
                    return clsAddr;
                }
            }
        }
        else
        {
            return clsObject.getID().getAddress();
        }
    }

    /**
     * If the object is the boot loader, modify its address to something
     * suitable for MAT Originally used when it was thought that MAT had to have
     * the boot loader at location 0 Could be used to change a zero boot loader
     * address to a made-up value.
     * 
     * @param bootLoaderAddress
     * @param objAddress
     * @return
     */
    private long fixBootLoaderAddress(long bootLoaderAddress, long objAddress)
    {
        // Fix-up for MAT which presumes the boot loader is at address 0
        // if (objAddress == bootLoaderAddress) objAddress = 0x0L;
        // This doesn't seem to be critical
        return objAddress;
    }

    /**
     * Search a frame for any pointers to heap objects Throws MemoryAccessError
     * so the caller can decide if that is possible or bad
     * 
     * @param pointerSize
     *            in bytes
     * @param threadAddress
     * @param thr
     * @param ip
     * @param searchSize
     *            How many bytes to search
     * @param rootType
     *            type of GC root e.g. native root, Java frame root etc.
     * @param gc
     *            The map of roots - from object id to description of list of
     *            roots of that object
     * @param searchedAddresses
     *            Add any locations containing valid objects to this set of
     *            searched locations
     * @param excludedAddresses
     *            Don't use any locations which have already been used
     * @throws CorruptDataException
     *             , MemoryAccessException
     */
    private void searchFrame(int pointerSize, long threadAddress,
                    HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thrs, ImagePointer ip, long searchSize,
                    int rootType, HashMapIntObject<List<XGCRootInfo>> gc, Set<Long> searchedAddresses,
                    Set<Long> excludedAddresses) throws CorruptDataException, MemoryAccessException
    {
        debugPrint("searching thread " + format(threadAddress) + " " + format(ip.getAddress()) + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + format(searchSize) + " " + rootType); //$NON-NLS-1$
        long frameAddress;
        HashMapIntObject<List<XGCRootInfo>> thr = thrs.get(indexToAddress.reverse(threadAddress));
        frameAddress = threadAddress;
        // Read items off the frame
        final int pointerAdjust = searchSize >= 0 ? pointerSize : -pointerSize;
        for (long j = 0; Math.abs(j) < Math.abs(searchSize); j += pointerAdjust)
        {
            // getPointerAt indexes by bytes, not pointers size
            ImagePointer i2 = ip.getPointerAt(j);
            long location = ip.getAddress() + j;
            long addr = i2.getAddress();
            int id = indexToAddress.reverse(addr);
            if (addr != 0 && id >= 0)
            {
                // Found object
                if (excludedAddresses == null || !excludedAddresses.contains(location))
                {
                    if (searchedAddresses != null)
                        searchedAddresses.add(location);
                    if (thr != null)
                    {
                        // Add it to the thread roots
                        addRoot(thr, addr, frameAddress, rootType);
                        // Add it to the global GC roots
                        if (!useThreadRefsNotRoots)
                            addRoot(gc, addr, frameAddress, rootType);
                    }
                    else
                    {
                        // No thread information so make a global root
                        addRoot(gc, addr, frameAddress, rootType);
                    }
                }
            }
        }
    }

    /**
     * @param j2
     * @param ci
     * @param jlc
     * @param pointerSize
     *            size of pointer in bytes - used for correcting object sizes
     * @param listener
     *            for error reporting
     */
    private void genClass2(JavaClass j2, ClassImpl ci, ClassImpl jlc, int pointerSize, IProgressListener listener)
    {
        ci.setClassInstance(jlc);
        int size = 0;
        try
        {
            JavaObject object = j2.getObject();
            if (object != null)
            {
                size = getObjectSize(object, pointerSize);
                jlc.setHeapSizePerInstance(size);
            }
        }
        catch (IllegalArgumentException e)
        {
            // problems with getObject when the class is corrupt?
            listener.sendUserMessage(Severity.WARNING, Messages.DTFJIndexBuilder_ProblemGettingSizeOfJavaLangClass, e);
        }
        catch (CorruptDataException e)
        {
            // Javacore causes too many of these errors
            // listener.sendUserMessage(Severity.WARNING, "Problem setting size
            // of instance of java.lang.Class", e);
        }
        // TODO should we use segments to get the RAM/ROM class size?
        size += classSize(j2, listener);
        ci.setUsedHeapSize(size);
        // For calculating purge sizes
        objectToSize.set(ci.getObjectId(), size);
        jlc.addInstance(size);
        debugPrint("build class " + ci.getName() + " at " + ci.getObjectId() + " address " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + format(ci.getObjectAddress()) + " loader " + ci.getClassLoaderId() + " super " //$NON-NLS-1$ //$NON-NLS-2$
                        + ci.getSuperClassId() + " size " + ci.getUsedHeapSize()); //$NON-NLS-1$
    }

    private int classSize(JavaClass jc, IProgressListener listener)
    {
        long size = 0;
        try
        {
            // Try to accumulate the size of the actual class object
            JavaObject jo = jc.getObject();
            if (jo != null)
            {
                size += jo.getSize();
            }
        }
        catch (CorruptDataException e)
        {}
        for (Iterator i = jc.getDeclaredMethods(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingDeclaredMethods, jc))
                continue;
            JavaMethod jm = (JavaMethod) next;
            size += getMethodSize(jc, jm, listener);
        }
        return (int) size;
    }

    private int getMethodSize(JavaClass jc, JavaMethod jm, IProgressListener listener)
    {
        int size = 0;
        for (Iterator j = jm.getBytecodeSections(); j.hasNext();)
        {
            Object next2 = j.next();
            if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingBytecodeSections, jc, jm))
                continue;
            ImageSection is = (ImageSection) next2;
            final int bigSegment = 0x10000;
            long sizeSeg = checkSegmentSize(jc, jm, is, bigSegment,
                            Messages.DTFJIndexBuilder_UnexpectedBytecodeSectionSize, listener);
            size += sizeSeg;
            // debugPrint("Adding bytecode code section at
            // "+format(is.getBaseAddress().getAddress())+" size "+size);
        }
        for (Iterator j = jm.getCompiledSections(); j.hasNext();)
        {
            Object next2 = j.next();
            // 1.4.2 CorruptData
            if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingCompiledSections, jc, jm))
                continue;
            ImageSection is = (ImageSection) next2;
            final int bigSegment = 0x60000;
            long sizeSeg = checkSegmentSize(jc, jm, is, bigSegment,
                            Messages.DTFJIndexBuilder_UnexpectedCompiledCodeSectionSize, listener);
            size += sizeSeg;
        }
        return size;
    }

    /**
     * Avoid problems with bad compiled code segment sizes. Also Sov has some
     * negative sizes for bytecode sections.
     * 
     * @param jc
     * @param jm
     * @param is
     * @param bigSegment
     * @param message
     *            segment base {0} size {1} size limit {2} modifiers {3} class
     *            name {4} method name {5} sig {6}
     * @param listener
     * @return
     */
    private long checkSegmentSize(JavaClass jc, JavaMethod jm, ImageSection is, final int bigSegment, String message,
                    IProgressListener listener)
    {
        long sizeSeg = is.getSize();
        if (sizeSeg < 0 || sizeSeg >= bigSegment)
        {
            String clsName;
            String methName;
            String methSig;
            try
            {
                clsName = jc != null ? jc.getName() : ""; //$NON-NLS-1$
            }
            catch (CorruptDataException e)
            {
                clsName = e.toString();
            }
            try
            {
                methName = jm.getName();
            }
            catch (CorruptDataException e)
            {
                methName = e.toString();
            }
            try
            {
                methSig = jm.getSignature();
            }
            catch (CorruptDataException e)
            {
                methSig = e.toString();
            }
            if (msgNbigSegs-- > 0)
            {
                String mods = getModifiers(jm, listener);
                listener.sendUserMessage(Severity.INFO, MessageFormat.format(message, format(is.getBaseAddress()
                                .getAddress()), sizeSeg, bigSegment, mods, clsName, methName, methSig), null);
            }
            sizeSeg = 0;
        }
        return sizeSeg;
    }

    /**
     * Is a class finalizable? Is there a finalize method other than from
     * java.lang.Object?
     * 
     * @param c
     * @param listener
     * @return Class address if the objects of this class are finalizable
     */
    private long isFinalizable(JavaClass c, IProgressListener listener)
    {
        long ca = 0;
        String cn = null;
        try
        {
            cn = getClassName(c, listener);
            ca = getClassAddress(c, listener);
            while (getSuperclass(c, listener) != null)
            {
                String cn1 = getClassName(c, listener);
                long ca1 = getClassAddress(c, listener);
                for (Iterator it = c.getDeclaredMethods(); it.hasNext();)
                {
                    Object next = it.next();
                    if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingDeclaredMethods, c))
                        continue;
                    JavaMethod m = (JavaMethod) next;
                    try
                    {
                        if (m.getName().equals("finalize")) //$NON-NLS-1$
                        {
                            try
                            {
                                if (m.getSignature().equals("()V")) //$NON-NLS-1$
                                {
                                    // Correct signature
                                    return ca;
                                }
                            }
                            catch (CorruptDataException e)
                            {
                                // Unknown signature, so presume it is the
                                // finalize() method.
                                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_ProblemDetirminingFinalizeMethodSig, cn1,
                                                format(ca1)), e);
                                return ca;
                            }
                        }
                    }
                    catch (CorruptDataException e)
                    {
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ProblemDetirminingFinalizeMethod, cn1, format(ca1)),
                                        e);
                    }
                }
                c = getSuperclass(c, listener);
            }
        }
        catch (CorruptDataException e)
        {
            if (cn == null)
                cn = e.toString();
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                            Messages.DTFJIndexBuilder_ProblemDetirminingIfClassIsFinalizable, cn, format(ca)), e);
        }
        return 0L;
    }

    /**
     * @param m2
     * @param bootLoaderAddress
     * @param hm
     * @param jo
     * @param type
     * @param aa
     * @param arrayLen
     * @param listener
     *            To indicate progress/errors
     * @throws CorruptDataException
     */
    private void exploreArray(IndexWriter.Identifier m2, long bootLoaderAddress, HashMapIntObject<ClassImpl> hm,
                    JavaObject jo, JavaClass type, ArrayLong aa, int arrayLen, IProgressListener listener)
                    throws CorruptDataException
    {
        boolean primitive = isPrimitiveArray(type);
        if (!primitive)
        {
            // Do large arrays in pieces to try to avoid OutOfMemoryErrors
            int arrayStep = ARRAY_PIECE_SIZE;
            for (int arrayOffset = 0; arrayOffset < arrayLen; arrayOffset += arrayStep)
            {
                arrayStep = Math.min(arrayStep, arrayLen - arrayOffset);
                JavaObject refs[] = new JavaObject[arrayStep];
                if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
                try
                {
                    // - arraycopy doesn't check indices
                    // IllegalArgumentException from
                    // JavaObject.arraycopy
                    try
                    {
                        debugPrint("Array copy " + arrayOffset + " " + arrayLen + " " + arrayStep); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        jo.arraycopy(arrayOffset, refs, 0, arrayStep);
                    }
                    catch (IllegalArgumentException e)
                    {
                        String typeName;
                        try
                        {
                            typeName = type.getName();
                        }
                        catch (CorruptDataException e1)
                        {
                            typeName = e1.toString();
                        }
                        listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_ProblemReadingArray, typeName, arrayLen, arrayOffset,
                                        arrayStep, format(jo.getID().getAddress())), e);
                    }
                    int idx = arrayOffset;
                    for (JavaObject jao : refs)
                    {
                        if (jao != null)
                        {
                            // Add the non-null refs
                            long elementObjAddress = jao.getID().getAddress();
                            elementObjAddress = fixBootLoaderAddress(bootLoaderAddress, elementObjAddress);
                            int elementRef = m2.reverse(elementObjAddress);
                            if (elementRef < 0)
                            {
                                if (msgNinvalidArray-- > 0)
                                {
                                    String name;
                                    Exception e1 = null;
                                    if (debugInfo)
                                    {
                                        // Getting the class can be expensive for an unknown object,
                                        // so only do in debug mode to avoid rereading the dump
                                        try
                                        {
                                            JavaClass javaClass = jao.getJavaClass();
                                            name = javaClass != null ? javaClass.getName() : ""; //$NON-NLS-1$
                                        }
                                        catch (CorruptDataException e)
                                        {
                                            name = e.toString();
                                            e1 = e;
                                        }
                                    }
                                    else
                                    {
                                        name = "?";
                                    }
                                    String typeName;
                                    try
                                    {
                                        typeName = type.getName();
                                    }
                                    catch (CorruptDataException e)
                                    {
                                        typeName = e.toString();
                                        e1 = e;
                                    }
                                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                                    Messages.DTFJIndexBuilder_InvalidArrayElement,
                                                    format(elementObjAddress), name, idx, typeName, arrayLen, format(jo
                                                                    .getID().getAddress())), e1);
                                }
                            }
                            else
                            {
                                if (hm.get(elementRef) != null)
                                {
                                    if (verbose)
                                        debugPrint("Found class ref field " + elementRef + " from array " //$NON-NLS-1$ //$NON-NLS-2$
                                                        + m2.reverse(jo.getID().getAddress()));
                                    aa.add(elementObjAddress);
                                }
                                else
                                {
                                    if (verbose)
                                        debugPrint("Found obj ref field " + elementRef + " from array " //$NON-NLS-1$ //$NON-NLS-2$
                                                        + m2.reverse(jo.getID().getAddress()));
                                    aa.add(elementObjAddress);
                                }
                            }
                        }
                        ++idx;
                    }
                }
                catch (CorruptDataException e)
                {
                    String typeName;
                    try
                    {
                        typeName = type.getName();
                    }
                    catch (CorruptDataException e1)
                    {
                        typeName = e1.toString();
                    }
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingArray, typeName, arrayLen, arrayOffset,
                                    arrayStep, format(jo.getID().getAddress())), e);
                }
                catch (MemoryAccessException e)
                {
                    String typeName;
                    try
                    {
                        typeName = type.getName();
                    }
                    catch (CorruptDataException e1)
                    {
                        typeName = e1.toString();
                    }
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ProblemReadingArray, typeName, arrayLen, arrayOffset,
                                    arrayStep, format(jo.getID().getAddress())), e);
                }
            }
        }
    }

    /**
     * Tests whether an array is a primitive array (i.e. the elements are not
     * objects) Allows for a bug in DTFJ
     * 
     * @param type
     * @return true if the array elements are primitives
     * @throws CorruptDataException
     */
    public static boolean isPrimitiveArray(JavaClass type) throws CorruptDataException
    {
        // - getComponentType strips all of arrays instead of one
        if (type.getName().startsWith("[[")) //$NON-NLS-1$
            return false;
        JavaClass elemClass = type.getComponentType();
        boolean primitive = isPrimitiveName(elemClass.getName());
        return primitive;
    }

    /**
     * @param elemClass
     * @return true if the class is a primitive class (int, byte, float etc.)
     * @throws CorruptDataException
     */
    private boolean isPrimitive(JavaClass elemClass) throws CorruptDataException
    {
        boolean primitive = getSuperclass(elemClass, null) == null && !elemClass.getName().equals("java/lang/Object") //$NON-NLS-1$
                        && !Modifier.isInterface(elemClass.getModifiers());
        return primitive;
    }

    /**
     * @param m2
     * @param bootLoaderAddress
     * @param hm
     * @param jo
     * @param type
     * @param aa
     * @param verbose
     *            print out extra information
     * @param listener
     *            To indicate progress/errors
     * @throws CorruptDataException
     */
    private void exploreObject(IndexWriter.Identifier m2, long bootLoaderAddress, HashMapIntObject<ClassImpl> hm,
                    JavaObject jo, JavaClass type, ArrayLong aa, boolean verbose, IProgressListener listener)
                    throws CorruptDataException
    {
        if (verbose)
        {
            debugPrint("Exploring " + type.getName() + " at " + jo.getID().getAddress()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (JavaClass jc = type; jc != null; jc = getSuperclass(jc, listener))
        {
            for (Iterator ii = jc.getDeclaredFields(); ii.hasNext();)
            {
                Object next3 = ii.next();
                if (isCorruptData(next3, listener, Messages.DTFJIndexBuilder_CorruptDataReadingDeclaredFields, jc))
                    continue;
                JavaField jf = (JavaField) next3;
                if (!Modifier.isStatic(jf.getModifiers()))
                {
                    String typeName = type.getName();
                    String clsName = jc.getName();
                    String fieldName = jf.getName();
                    String sig = jf.getSignature();

                    if (sig.startsWith("[") || sig.startsWith("L")) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        try
                        {
                            Object obj;
                            try
                            {
                                obj = jf.get(jo);
                            }
                            catch (IllegalArgumentException e)
                            {
                                // - IllegalArgumentException
                                // instead of CorruptDataException or a partial
                                // JavaObject
                                obj = null;
                                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_ProblemReadingObjectFromField, clsName,
                                                fieldName, sig, typeName, format(jo.getID().getAddress())), e);
                            }
                            if (obj instanceof JavaObject)
                            {
                                JavaObject jo2 = (JavaObject) obj;
                                if (jo2 != null)
                                {
                                    long fieldObjAddress = jo2.getID().getAddress();
                                    fieldObjAddress = fixBootLoaderAddress(bootLoaderAddress, fieldObjAddress);
                                    int fieldRef = m2.reverse(fieldObjAddress);
                                    if (fieldRef < 0)
                                    {
                                        if (msgNinvalidObj-- > 0)
                                        {
                                            String name;
                                            Exception e1 = null;
                                            try
                                            {
                                                JavaClass javaClass = jo2.getJavaClass();
                                                name = javaClass != null ? javaClass.getName() : ""; //$NON-NLS-1$
                                            }
                                            catch (CorruptDataException e)
                                            {
                                                e1 = e;
                                                name = e.toString();
                                            }
                                            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                                            Messages.DTFJIndexBuilder_InvalidObjectFieldReference,
                                                            format(fieldObjAddress), name, clsName, fieldName, sig,
                                                            typeName, format(jo.getID().getAddress())), e1);
                                        }
                                    }
                                    else
                                    {
                                        // Do unexpected duplicate fields occur?
                                        // for (IteratorLong il = aa.iterator();
                                        // il.hasNext(); ) {
                                        // if (il.next() == fieldObjAddress)
                                        // debugPrint("duplicate field value
                                        // "+format(fieldObjAddress)+" from
                                        // "+format(jo.getID().getAddress())+"
                                        // "+m2.reverse(jo.getID().getAddress())+"
                                        // "+jo.getJavaClass().getName()+"="+jc.getName()+"."+jf.getName()+":"+jf.getSignature());
                                        // }
                                        if (verbose)
                                        {
                                            if (hm.get(fieldRef) != null)
                                            {
                                                debugPrint("Found class ref field " + fieldRef + " from " //$NON-NLS-1$ //$NON-NLS-2$
                                                                + m2.reverse(jo.getID().getAddress()));
                                            }
                                            else
                                            {
                                                debugPrint("Found obj ref field " + fieldRef + " from " //$NON-NLS-1$ //$NON-NLS-2$
                                                                + m2.reverse(jo.getID().getAddress()));
                                            }
                                        }
                                        aa.add(fieldObjAddress);
                                    }
                                }
                            }
                        }
                        catch (CorruptDataException e)
                        {
                            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_ProblemReadingObjectFromField, clsName,
                                            fieldName, sig, typeName, format(jo.getID().getAddress())), e);
                        }
                        catch (MemoryAccessException e)
                        {
                            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_ProblemReadingObjectFromField, clsName,
                                            fieldName, sig, typeName, format(jo.getID().getAddress())), e);
                        }
                    }
                    else
                    {
                        // primitive field
                    }
                }
            }
        }
    }

    /**
     * @param gc
     * @param ci
     */
    private void addRoot(HashMapIntObject<List<XGCRootInfo>> gc, long obj, long ctx, int type)
    {
        XGCRootInfo rri = new XGCRootInfo(obj, ctx, newRootType(type));
        rri.setContextId(indexToAddress.reverse(rri.getContextAddress()));
        rri.setObjectId(indexToAddress.reverse(rri.getObjectAddress()));
        int objectId = rri.getObjectId();
        List<XGCRootInfo> rootsForID = gc.get(objectId);
        if (rootsForID == null)
        {
            rootsForID = new ArrayList<XGCRootInfo>();
            gc.put(objectId, rootsForID);
        }
        rootsForID.add(rri);
        // debugPrint("Root "+format(obj));
        int clsId = objectToClass.get(objectId);
        ClassImpl cls = idToClass.get(clsId);
        // debugPrint("objid "+objectId+" clsId "+clsId+" "+cls);
        String clsName = cls != null ? cls.getName() : ""; //$NON-NLS-1$
        String desc = "" + format(obj) + " " + objectId + " ctx " + format(ctx) + " " + rri.getContextId() + " type:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                        + clsName;
        // 32 busy monitor
        // 64 java local
        // 256 thread obj
        debugPrint("Root " + type + " " + desc); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * First stage of building a class object Get the right class loader, the
     * superclass, the fields and the constant pool
     * 
     * @param j2
     * @param hm
     * @param bootLoaderAddress
     * @param superAddress
     *            If non-zero override superclass address with this.
     * @param listener
     *            To indicate progress/errors
     * @return the new class
     */
    private ClassImpl genClass(JavaClass j2, HashMapIntObject<ClassImpl> hm, long bootLoaderAddress, long sup,
                    IProgressListener listener)
    {
        try
        {
            long claddr = getClassAddress(j2, listener);

            long loader;
            try
            {
                // Set up class loaders
                JavaClassLoader load = getClassLoader(j2, listener);
                if (load == null)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnableToFindClassLoader, j2.getName(), format(claddr)),
                                    null);
                }
                loader = getLoaderAddress(load, bootLoaderAddress);
            }
            catch (CorruptDataException e)
            {
                // Unable to find class loader
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_UnableToFindClassLoader, j2.getName(), format(claddr)), e);
                loader = fixBootLoaderAddress(bootLoaderAddress, bootLoaderAddress);
            }

            if (sup == 0)
            {
                JavaClass superClass = getSuperclass(j2, listener);
                sup = superClass != null ? getClassAddress(superClass, listener) : 0L;
            }
            int superId;
            if (sup != 0)
            {
                superId = indexToAddress.reverse(sup);
                if (superId < 0)
                {
                    // We have a problem at this point - the class has a real
                    // superclass address, but a bad id.
                    // If the address is non-zero ClassImpl will use the id,
                    // which can cause exceptions inside of MAT
                    // so clear the address.
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_SuperclassNotFound, format(sup), superId, format(claddr),
                                    indexToAddress.reverse(claddr), j2.getName()), null);
                    sup = 0;
                }
            }
            else
            {
                superId = -1;
            }

            ArrayList<FieldDescriptor> al = new ArrayList<FieldDescriptor>();
            ArrayList<Field> al2 = new ArrayList<Field>();

            // Do we need the superclass as an explicit link?
            if (sup != 0)
            {
                ObjectReference val = new ObjectReference(null, sup);
                Field f = new Field("<super>", IObject.Type.OBJECT, val); //$NON-NLS-1$
                al2.add(f);
            }

            // We don't need to deal with superclass static fields as these are
            // maintained by the superclass
            for (Iterator f1 = j2.getDeclaredFields(); f1.hasNext();)
            {
                Object next = f1.next();
                if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingDeclaredFields, j2))
                    continue;
                JavaField jf = (JavaField) next;
                if (Modifier.isStatic(jf.getModifiers()))
                {
                    Object val = null;
                    try
                    {
                        // CorruptDataException when reading
                        // negative byte/shorts
                        Object o = jf.get(null);
                        if (o instanceof JavaObject)
                        {
                            JavaObject jo = (JavaObject) o;
                            long address = jo.getID().getAddress();
                            val = new ObjectReference(null, address);
                        }
                        else
                        {
                            if (o instanceof Number || o instanceof Character || o instanceof Boolean || o == null)
                            {
                                val = o;
                            }
                            else
                            {
                                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_UnexpectedValueForStaticField, o, jf
                                                                .getName(), jf.getSignature(), j2.getName(),
                                                format(claddr)), null);
                            }
                        }
                    }
                    catch (IllegalArgumentException e)
                    {
                        // IllegalArgumentException from static
                        // JavaField.get()
                        listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_InvalidStaticField, jf.getName(), jf.getSignature(),
                                        j2.getName(), format(claddr)), e);
                    }
                    catch (CorruptDataException e)
                    {
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_InvalidStaticField, jf.getName(), jf.getSignature(),
                                        j2.getName(), format(claddr)), e);
                    }
                    catch (MemoryAccessException e)
                    {
                        listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_InvalidStaticField, jf.getName(), jf.getSignature(),
                                        j2.getName(), format(claddr)), e);
                    }
                    Field f = new Field(jf.getName(), signatureToType(jf.getSignature()), val);
                    debugPrint("Adding static field " + jf.getName() + " " + f.getType() + " " + val + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                    + f.getValue());
                    al2.add(f);
                }
                else
                {
                    FieldDescriptor fd = new FieldDescriptor(jf.getName(), signatureToType(jf.getSignature()));
                    al.add(fd);
                }
            }

            // Add java.lang.Class instance fields as pseudo static fields
            JavaObject joc;
            try
            {
                joc = j2.getObject();
            }
            catch (CorruptDataException e)
            {
                // Javacore - fails
                joc = null;
            }
            catch (IllegalArgumentException e)
            {
                // IllegalArgumentException if object address not
                // found
                listener.sendUserMessage(Severity.ERROR, Messages.DTFJIndexBuilder_ProblemBuildingClassObject, e);
                joc = null;
            }
            JavaClass j3;
            if (joc != null)
            {
                try
                {
                    j3 = joc.getJavaClass();
                }
                catch (CorruptDataException e)
                {
                    // Corrupt - fails for dump.xml
                    long objAddr = joc.getID().getAddress();
                    if (msgNtypeForClassObject-- > 0) listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_UnableToFindTypeOfObject, format(objAddr),
                                    format(claddr), j2.getName()), e);
                    j3 = null;
                }
            }
            else
            {
                // No Java object for class, so skip looking for fields
                if (j2.getID() != null)
                {
                    if (debugInfo) debugPrint("No Java object for " + getClassName(j2, listener) + " at " + format(j2.getID().getAddress())); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                {
                    if (debugInfo) debugPrint("No Java object for " + getClassName(j2, listener)); //$NON-NLS-1$
                }
                j3 = null;
            }
            for (; j3 != null; j3 = getSuperclass(j3, listener))
            {
                for (Iterator f1 = j3.getDeclaredFields(); f1.hasNext();)
                {
                    Object next = f1.next();
                    if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingDeclaredFields, j3))
                        continue;
                    JavaField jf = (JavaField) next;
                    if (!Modifier.isStatic(jf.getModifiers()))
                    {
                        Object val = null;
                        try
                        {
                            // CorruptDataException when reading
                            // negative byte/shorts
                            Object o = jf.get(joc);
                            if (o instanceof JavaObject)
                            {
                                JavaObject jo = (JavaObject) o;
                                long address = jo.getID().getAddress();
                                val = new ObjectReference(null, address);
                            }
                            else
                            {
                                if (o instanceof Number)
                                {
                                    val = o;
                                }
                            }
                        }
                        catch (CorruptDataException e)
                        {
                            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_InvalidField, jf.getName(), jf.getSignature(), j3
                                                            .getName(), format(claddr)), e);
                        }
                        catch (MemoryAccessException e)
                        {
                            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_InvalidField, jf.getName(), jf.getSignature(), j3
                                                            .getName(), format(claddr)), e);
                        }
                        // This is an instance field in the Java object
                        // representing the class, becoming a pseudo-static
                        // field in the MAT class
                        Field f = new Field("<" + jf.getName() + ">", signatureToType(jf.getSignature()), val); //$NON-NLS-1$ //$NON-NLS-2$
                        al2.add(f);
                    }
                }
            }

            // Add constant pool entries as pseudo fields
            int cpindex = 0;
            Iterator f1;
            try
            {
                f1 = j2.getConstantPoolReferences();
            }
            catch (IllegalArgumentException e)
            {
                // IllegalArgumentException from
                // getConstantPoolReferences (bad ref?)
                listener.sendUserMessage(Severity.ERROR, Messages.DTFJIndexBuilder_ProblemBuildingClassObject, e);
                f1 = Collections.EMPTY_LIST.iterator();
            }
            for (; f1.hasNext();)
            {
                Object next = f1.next();
                if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingConstantPoolReferences,
                                j2))
                    continue;
                Object val = null;
                JavaObject jo;
                long address;
                if (next instanceof JavaObject)
                {
                    jo = (JavaObject) next;
                    address = jo.getID().getAddress();
                }
                else if (next instanceof JavaClass)
                {
                    JavaClass jc = (JavaClass) next;
                    address = getClassAddress(jc, listener);
                }
                else
                {
                    // Unexpected constant pool entry
                    continue;
                }
                val = new ObjectReference(null, address);
                Field f = new Field("<constant pool[" + (cpindex++) + "]>", IObject.Type.OBJECT, val); //$NON-NLS-1$ //$NON-NLS-2$
                al2.add(f);
            }

            // Add the MAT descriptions of the fields
            Field[] statics = al2.toArray(new Field[0]);
            FieldDescriptor[] fld = al.toArray(new FieldDescriptor[0]);
            String cname = getMATClassName(j2, listener);
            ClassImpl ci = new ClassImpl(claddr, cname, sup, loader, statics, fld);
            // Fix the indexes
            final long claddr2 = ci.getObjectAddress();
            final int clsId = indexToAddress.reverse(claddr2);
            // debugPrint("Setting class "+format(claddr)+" "+clsId+"
            // "+format(claddr2));
            if (clsId >= 0)
            {
                ci.setObjectId(clsId);
            }
            else
            {
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ClassAtAddressNotFound, format(claddr), clsId, j2.getName()),
                                null);
            }
            if (sup != 0)
            {
                // debugPrint("Super "+sup+" "+superId);
                // superId is valid
                ci.setSuperClassIndex(superId);
            }

            int loaderId = indexToAddress.reverse(loader);
            if (loaderId >= 0)
            {
                ci.setClassLoaderIndex(loaderId);
            }
            else
            {
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ClassLoaderAtAddressNotFound, format(loader), loaderId,
                                format(claddr), clsId, j2.getName()), null);
            }

            // debugPrint("Build "+ci.getName()+" at "+format(claddr2));
            hm.put(indexToAddress.reverse(claddr), ci);
            return ci;
        }
        catch (CorruptDataException e)
        {
            listener.sendUserMessage(Severity.ERROR, Messages.DTFJIndexBuilder_ProblemBuildingClass, e);
            return null;
        }
    }

    /**
     * Converts the DTFJ type to the MAT type
     * 
     * @param sig
     * @return
     * @throws CorruptDataException
     */
    static int signatureToType(String sig) throws CorruptDataException
    {
        int ret;
        switch (sig.charAt(0))
        {
            case 'L':
                ret = IObject.Type.OBJECT;
                break;
            case '[':
                ret = IObject.Type.OBJECT;
                break;
            case 'Z':
                ret = IObject.Type.BOOLEAN;
                break;
            case 'B':
                ret = IObject.Type.BYTE;
                break;
            case 'C':
                ret = IObject.Type.CHAR;
                break;
            case 'S':
                ret = IObject.Type.SHORT;
                break;
            case 'I':
                ret = IObject.Type.INT;
                break;
            case 'J':
                ret = IObject.Type.LONG;
                break;
            case 'F':
                ret = IObject.Type.FLOAT;
                break;
            case 'D':
                ret = IObject.Type.DOUBLE;
                break;
            default:
                ret = -1;
        }
        return ret;
    }

    /**
     * @param load
     * @param bootLoaderAddress
     * @return
     * @throws CorruptDataException
     */
    private long getLoaderAddress(JavaClassLoader load, long bootLoaderAddress) throws CorruptDataException
    {
        long loader;
        if (load == null)
        {
            loader = bootLoaderAddress;
        }
        else
        {
            JavaObject loaderObject = load.getObject();
            if (loaderObject == null)
            {
                loader = bootLoaderAddress;
            }
            else
            {
                loader = loaderObject.getID().getAddress();
            }
        }
        loader = fixBootLoaderAddress(bootLoaderAddress, loader);
        return loader;
    }

    /**
     * Get the name for a class, but handle errors
     * @param javaClass
     * @param listen
     * @return
     * @throws CorruptDataException
     */
    private String getClassName(JavaClass javaClass, IProgressListener listen) throws CorruptDataException
    {
        String name;
        try
        {
            name = javaClass.getName();
        }
        catch (CorruptDataException e)
        {
            long id = getClassAddress(javaClass, listen);
            name = "corruptClassName@" + format(id); //$NON-NLS-1$
            try
            {
                if (javaClass.isArray())
                {
                    name = "[LcorruptArrayClassName@" + format(id) + ";"; //$NON-NLS-1$ //$NON-NLS-2$
                    JavaClass comp = javaClass.getComponentType();
                    String compName = getClassName(comp, listen);
                    if (compName.startsWith("[")) //$NON-NLS-1$
                        name = "[" + compName; //$NON-NLS-1$
                    else
                        name = "[L" + compName + ";"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (CorruptDataException e2)
            {
            }
        }
        return name;
    }
    /**
     * @param javaClass
     * @return the type as a signature
     * @throws CorruptDataException
     *             Doesn't work for arrays - but should not find any in constant
     *             pool
     */
    private String getClassSignature(JavaClass javaClass) throws CorruptDataException
    {
        String sig;
        sig = "L" + javaClass.getName() + ";"; //$NON-NLS-1$ //$NON-NLS-2$
        return sig;
    }

    private static final String primitives[] = { Boolean.TYPE.getName(), Byte.TYPE.getName(), Short.TYPE.getName(),
                    Character.TYPE.getName(), Integer.TYPE.getName(), Long.TYPE.getName(), Float.TYPE.getName(),
                    Double.TYPE.getName(), Void.TYPE.getName() };
    private static final HashSet<String> primSet = new HashSet<String>(Arrays.asList(primitives));

    private static boolean isPrimitiveName(String name)
    {
        return primSet.contains(name);
    }

    /**
     * Get the address of the superclass object Avoid DTFJ bugs
     * 
     * @param j2
     * @param listener
     *            for logging
     * @return
     */
    private JavaClass getSuperclass(JavaClass j2, IProgressListener listener)
    {
        JavaClass sup = null;
        try
        {
            sup = j2.getSuperclass();

            // superclass for array can return java.lang.Object from
            // another dump!
            if (sup != null)
            {
                ImagePointer supAddr = sup.getID();
                ImagePointer clsAddr = sup.getID();
                supAddr = clsAddr;
                // Synthetic classes can have a null ID
                if (supAddr != null && clsAddr != null)
                {
                    ImageAddressSpace supAddressSpace = supAddr.getAddressSpace();
                    ImageAddressSpace clsAddressSpace = clsAddr.getAddressSpace();
                    if (!supAddressSpace.equals(clsAddressSpace))
                    {
                        if (supAddressSpace != clsAddressSpace)
                        {
                            if (listener != null)
                                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_SuperclassInWrongAddressSpace, j2.getName(),
                                                supAddressSpace, clsAddressSpace), null);
                            sup = null;
                        }
                        else
                        {
                            // ImageAddressSpace.equals broken -
                            // returns false
                            if (listener != null && msgNbrokenEquals-- > 0)
                                listener.sendUserMessage(Severity_INFO, MessageFormat.format(
                                                Messages.DTFJIndexBuilder_ImageAddressSpaceEqualsBroken,
                                                supAddressSpace, clsAddressSpace, System.identityHashCode(supAddr),
                                                System.identityHashCode(clsAddr)), null);
                        }
                    }
                }
            }

            if (sup == null)
            {
                // debugPrint("No superclass for "+j2.getName());
                if (j2.isArray() && j2.getObject() != null && j2.getObject().getJavaClass().getSuperclass() != null)
                {
                    // Fix DTFJ bug - find java.lang.Object to use as the
                    // superclass
                    for (sup = j2.getObject().getJavaClass(); sup.getSuperclass() != null; sup = sup.getSuperclass())
                    {}
                    if (listener != null)
                        listener.sendUserMessage(Severity_INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_NoSuperclassForArray, j2.getName(), sup.getName()),
                                        null);
                }
            }
            else
            {
                // J9 DTFJ returns java.lang.Object for interfaces
                // Sov DTFJ returns java.lang.Object for primitives
                // or interfaces
                if (Modifier.isInterface(j2.getModifiers()))
                {
                    if (listener != null && msgNbrokenInterfaceSuper-- > 0)
                        listener.sendUserMessage(Severity_INFO, MessageFormat.format(
                                        Messages.DTFJIndexBuilder_InterfaceShouldNotHaveASuperclass, j2.getName(), sup
                                                        .getName()), null);
                    sup = null;
                }
                else
                {
                    String name = j2.getName();
                    if (isPrimitiveName(name))
                    {
                        if (listener != null)
                            listener.sendUserMessage(Severity_INFO, MessageFormat.format(
                                            Messages.DTFJIndexBuilder_PrimitiveShouldNotHaveASuperclass, j2.getName(),
                                            sup.getName()), null);
                        sup = null;
                    }
                }
            }
            return sup;
        }
        catch (CorruptDataException e)
        {
            long addr = getClassAddress(j2, listener);
            String name;
            try
            {
                name = getClassName(j2, listener);
            }
            catch (CorruptDataException e2)
            {
                name = j2.getClass().getName();
            }
            if (listener != null && msgNgetSuperclass-- > 0)
                listener.sendUserMessage(Severity.WARNING, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ProblemGettingSuperclass, name, format(addr)), e);
            return sup; // Just for Javacore
        }
    }

    /**
     * Basic class loader finder - copes with arrays not having a loader, use
     * component type loader instead
     * 
     * @param j2
     * @param listener
     *            for error messages
     * @return
     * @throws CorruptDataException
     */
    private JavaClassLoader getClassLoader1(JavaClass j2, IProgressListener listener) throws CorruptDataException
    {
        JavaClassLoader load;
        // Fix up possible problem with arrays not having a class loader
        // Use the loader of the component type instead
        for (JavaClass j3 = j2; (load = j3.getClassLoader()) == null && j3.isArray(); j3 = j3.getComponentType())
        {
            if (msgNmissingLoaderMsg-- > 0)
                listener.sendUserMessage(Severity_INFO, MessageFormat.format(Messages.DTFJIndexBuilder_NoClassLoader,
                                j3.getName(), j3.getComponentType().getName()), null);
        }
        return load;
    }

    /**
     * General class loader finder
     * 
     * @param j1
     * @param listener
     *            for error messages
     * @return
     * @throws CorruptDataException
     */
    private JavaClassLoader getClassLoader(JavaClass j1, IProgressListener listener) throws CorruptDataException
    {
        JavaClassLoader load;
        try
        {
            load = getClassLoader1(j1, listener);
        }
        catch (CorruptDataException e)
        {
            load = getClassLoader2(j1, listener);
            if (load != null)
                return load;
            throw e;
        }
        if (load == null)
        {
            load = getClassLoader2(j1, listener);
        }
        return load;
    }

    /**
     * @param j1
     * @return
     * @throws CorruptDataException
     */
    private JavaClassLoader getClassLoader2(JavaClass j1, IProgressListener listener) throws CorruptDataException
    {
        for (Iterator i = run.getJavaClassLoaders(); i.hasNext();)
        {
            Object next = i.next();
            if (isCorruptData(next, listener, Messages.DTFJIndexBuilder_CorruptDataReadingClassLoaders1, run))
                continue;
            JavaClassLoader jcl = (JavaClassLoader) next;
            for (Iterator j = jcl.getDefinedClasses(); j.hasNext();)
            {
                Object next2 = j.next();
                if (isCorruptData(next2, listener, Messages.DTFJIndexBuilder_CorruptDataReadingClasses, jcl))
                    continue;
                JavaClass j2 = (JavaClass) next2;
                if (j2.equals(j1)) { return jcl; }
            }
        }
        return null;
    }

    /**
     * Convert an address to a 0x hex number
     * 
     * @param address
     * @return A string representing the address
     */
    private static String format(long address)
    {
        return "0x" + Long.toHexString(address); //$NON-NLS-1$
    }

    /**
     * Convert the DTFJ version of the class name to the MAT version The array
     * suffix is important for MAT operation - old J9 [[[java/lang/String ->
     * java.lang.String[][][] [char -> char[] 1.4.2, and new J9 after fix for
     * [[[Ljava/lang/String; -> java.lang.String[][][] [C -> char[]
     * 
     * @param j2
     * @param listener for messages
     * @return The MAT version of the class name
     * @throws CorruptDataException
     */
    private String getMATClassName(JavaClass j2, IProgressListener listener) throws CorruptDataException
    {
        // MAT expects the name with $, but with [][] for the dimensions
        String d = getClassName(j2, listener).replace('/', '.');
        // debugPrint("d = "+d);
        int dim = d.lastIndexOf("[") + 1; //$NON-NLS-1$
        int i = dim;
        int j = d.length();
        // Does the class name have L and semicolon around it
        if (d.charAt(j - 1) == ';')
        {
            // If so, remove them
            --j;
            if (d.charAt(i) == 'L')
                ++i;
            d = d.substring(i, j);
        }
        else
        {
            d = d.substring(i);
            // Fix up primitive type names
            // DTFJ J9 array names are okay (char etc.)
            if (d.equals("Z")) //$NON-NLS-1$
                d = "boolean"; //$NON-NLS-1$
            else if (d.equals("B")) //$NON-NLS-1$
                d = "byte"; //$NON-NLS-1$
            else if (d.equals("S")) //$NON-NLS-1$
                d = "short"; //$NON-NLS-1$
            else if (d.equals("C")) //$NON-NLS-1$
                d = "char"; //$NON-NLS-1$
            else if (d.equals("I")) //$NON-NLS-1$
                d = "int"; //$NON-NLS-1$
            else if (d.equals("F")) //$NON-NLS-1$
                d = "float"; //$NON-NLS-1$
            else if (d.equals("J")) //$NON-NLS-1$
                d = "long"; //$NON-NLS-1$
            else if (d.equals("D")) //$NON-NLS-1$
                d = "double"; //$NON-NLS-1$
            else if (d.startsWith("L")) //$NON-NLS-1$
                // javacore reader bug - no semicolon
                d = d.substring(1);
        }
        // Convert to MAT style array signature
        for (; dim > 0; --dim)
        {
            d = d + "[]"; //$NON-NLS-1$
        }
        // debugPrint("d2 = "+d);
        return d;
    }

    /**
     * Check that indices look valid
     * 
     * @param listener
     */
    private void validateIndices(IProgressListener listener)
    {
        final int maxIndex = indexToAddress.size();
        long prevAddress = -1;
        int nObjs = 0;
        int nObjsFromClass = 0;
        int nCls = 0;
        for (int i = 0; i < maxIndex; ++i)
        {
            long addr = indexToAddress.get(i);
            if (prevAddress == addr)
            {
                String desc = objDesc(i);
                int j = indexToAddress.reverse(addr);
                String desc2 = objDesc(j);
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_IndexAddressHasSameAddressAsPrevious, i, desc, format(addr),
                                desc2), null);
            }
            if (prevAddress > addr)
            {
                String desc = objDesc(i);
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_IndexAddressIsSmallerThanPrevious, i, desc, format(addr),
                                format(prevAddress)), null);
            }
            prevAddress = addr;
            int j = indexToAddress.reverse(addr);
            if (i != j)
            {
                String desc1 = objDesc(i);
                String desc2 = objDesc(j);
                listener.sendUserMessage(Severity.ERROR,
                                MessageFormat.format(Messages.DTFJIndexBuilder_IndexAddressFoundAtOtherID, i,
                                                format(addr), j, desc1, desc2), null);
            }
            int clsId = objectToClass.get(i);
            if (clsId < 0)
            {
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_ClassIDNotFound, i, format(addr), clsId), null);
            }
            else
            {
                ClassImpl ci = idToClass.get(clsId);
                if (ci == null)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ClassImplNotFound, i, format(addr), clsId), null);
                }
            }
            ClassImpl ci = idToClass.get(i);
            if (ci == null)
            {
                ++nObjs;
                // Ordinary object
                int size = arrayToSize.get(i);
                if (size < 0)
                {
                    ci = idToClass.get(clsId);
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_IndexAddressNegativeArraySize, i, format(addr), size, ci
                                                    .getTechnicalName()), null);
                }
            }
            else
            {
                ++nCls;
                long addr2 = ci.getObjectAddress();
                if (addr != addr2)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ClassIndexAddressNotEqualClassObjectAddress, i,
                                    format(addr), format(addr2), ci.getTechnicalName()), null);
                }
                int id = ci.getObjectId();
                if (i != id)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ClassIndexNotEqualClassObjectID, i, format(addr), id, ci
                                                    .getTechnicalName()), null);
                }
                int clsId2 = ci.getClassId();
                if (clsId != clsId2)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ClassIndexAddressTypeIDNotEqualClassImplClassId, i,
                                    format(addr), clsId, clsId2, ci.getTechnicalName()), null);
                }
                long ldrAddr = ci.getClassLoaderAddress();
                int ldr = ci.getClassLoaderId();
                if (ldr < 0)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_ClassIndexAddressNoLoaderID, i, format(addr), clsId, ldr,
                                    format(ldrAddr), ci.getTechnicalName()), null);
                }
                nObjsFromClass += ci.getNumberOfObjects();
            }
        }
        if (nObjsFromClass != nObjs + nCls)
        {
            listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                            Messages.DTFJIndexBuilder_ObjectsFoundButClassesHadObjectsAndClassesInTotal, nObjs, nCls,
                            nObjsFromClass), null);
        }
        // Check some GC information
        for (IteratorInt it = gcRoot.keys(); it.hasNext();)
        {
            int idx = it.next();
            if (idx < 0 || idx >= maxIndex)
            {
                listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                Messages.DTFJIndexBuilder_GCRootIDOutOfRange, idx, maxIndex), null);
            }
            for (ListIterator<XGCRootInfo> it2 = gcRoot.get(idx).listIterator(); it2.hasNext();)
            {
                XGCRootInfo ifo = it2.next();
                int objid = ifo.getObjectId();
                if (objid != idx)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageFormat.format(
                                    Messages.DTFJIndexBuilder_GCRootIDDoesNotMatchIndex, objid, idx), null);
                }
            }
        }
        // Force a GC
        /*
         * try { byte b[] = new byte[1500000000]; } catch (OutOfMemoryError e) {
         * byte b[] = new byte[100000]; } try { byte b[] = new byte[1400000000];
         * } catch (OutOfMemoryError e) { }
         */
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.mat.parser.IIndexBuilder#init(java.io.File,
     * java.lang.String)
     */
    public void init(File file, String prefix)
    {
        dump = file;
        pfx = prefix;
    }

    /**
     * Helper method to get a cached DTFJ image. Uses soft references to avoid
     * running out of memory.
     * 
     * @param format
     *            The id of the DTFJ plugin
     * @throws Error
     *             , IOException
     */
    public static Image getDump(File dump, Serializable format) throws Error, IOException
    {
        SoftReference<Image> softReference = imageMap.get(dump);
        Image im;
        if (softReference != null)
        {
            im = softReference.get();
            if (im != null)
                return im;
        }
        im = getUncachedDump(dump, format);
        imageMap.put(dump, new SoftReference<Image>(im));
        return im;
    }

    /**
     * Forget about the cached version of the dump
     * 
     * @param dump
     */
    public static void clearCachedDump(File dump)
    {
        imageMap.remove(dump);
        // There is no way to close a DTFJ image, so GC and finalize to attempt
        // to clean up any temporary files
        System.gc();
        System.runFinalization();
    }

    /**
     * Forget about all cached dumps
     */
    public static void clearCachedDumps()
    {
        imageMap.clear();
        // There is no way to close a DTFJ image, so GC and finalize to attempt
        // to clean up any temporary files
        System.gc();
        System.runFinalization();
    }

    /**
     * Helper method to get a DTFJ image
     * 
     * @param format
     *            The MAT description of the dump type e.g. DTFJ-J9)
     * @throws Error
     *             , IOException
     */
    public static Image getUncachedDump(File dump, Serializable format) throws Error, IOException
    {
        return getDynamicDTFJDump(dump, format);
    }

    /**
     * Get a DTFJ image dynamically using Eclipse extension points to find the
     * factory.
     * 
     * @param dump
     * @param format
     * @return
     * @throws IOException
     */
    private static Image getDynamicDTFJDump(File dump, Serializable format) throws IOException
    {
        Image image;
        IExtensionRegistry reg = Platform.getExtensionRegistry();
        IExtensionPoint point = reg.getExtensionPoint("com.ibm.dtfj.api", "imagefactory"); //$NON-NLS-1$ //$NON-NLS-2$

        if (point != null)
        {
            // Find all the DTFJ implementations
            for (IExtension ex : point.getExtensions())
            {
                // Find all the factories
                for (IConfigurationElement el : ex.getConfigurationElements())
                {
                    if (el.getName().equals("factory")) //$NON-NLS-1$
                    {
                        String id = el.getAttribute("id"); //$NON-NLS-1$
                        // Have we found the right factory?
                        if (id != null && id.equals(format))
                        {
                            File dumpFile = null, metaFile = null;
                            try
                            {
                                // Get the ImageFactory
                                ImageFactory fact = (ImageFactory) el.createExecutableExtension("action"); //$NON-NLS-1$

                                String name = dump.getName();

                                // Find the content types of the dump
                                FileInputStream is = null;
                                IContentType ct0, cts[], cts2[];
                                // The default type
                                try
                                {
                                    is = new FileInputStream(dump);
                                    ct0 = Platform.getContentTypeManager().findContentTypeFor(is, name);
                                }
                                finally
                                {
                                    if (is != null)
                                        is.close();
                                }
                                // Types based on file name
                                try
                                {
                                    is = new FileInputStream(dump);
                                    cts = Platform.getContentTypeManager().findContentTypesFor(is, name);
                                }
                                finally
                                {
                                    if (is != null)
                                        is.close();
                                }
                                // Types not based on file name
                                try
                                {
                                    is = new FileInputStream(dump);
                                    cts2 = Platform.getContentTypeManager().findContentTypesFor(is, null);
                                }
                                finally
                                {
                                    if (is != null)
                                        is.close();
                                }

                                // See if the supplied dump matches any of the
                                // content types for the factory
                                for (IConfigurationElement el2 : el.getChildren())
                                {
                                    if (!el2.getName().equals("content-types")) //$NON-NLS-1$
                                        continue;

                                    String extId = el2.getAttribute("dump-type"); //$NON-NLS-1$
                                    String metaId = el2.getAttribute("meta-type"); //$NON-NLS-1$

                                    IContentType cext = Platform.getContentTypeManager().getContentType(extId);
                                    IContentType cmeta = Platform.getContentTypeManager().getContentType(metaId);

                                    if (cmeta != null)
                                    {
                                        // Found a metafile description
                                        boolean foundext[] = new boolean[1];
                                        boolean foundmeta[] = new boolean[1];
                                        String actualExts[] = getActualExts(cext, name, ct0, cts, cts2, foundext);
                                        String actualMetaExts[] = getActualExts(cmeta, name, ct0, cts, cts2, foundmeta);
                                        String possibleExts[] = getPossibleExts(cext);
                                        String possibleMetaExts[] = getPossibleExts(cmeta);

                                        // Is the supplied file a dump or a
                                        // meta. Decide which to try first.
                                        boolean extFirst = foundext[0];
                                        for (int i = 0; i < 2; ++i, extFirst = !extFirst)
                                        {
                                            if (extFirst)
                                            {
                                                for (String ext : actualExts)
                                                {
                                                    for (String metaExt : possibleMetaExts)
                                                    {
                                                        String metaExt1 = ext.equals("") && !metaExt.equals("") ? "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                                                        + metaExt : metaExt;
                                                        String ext1 = metaExt.equals("") && !ext.equals("") ? "." + ext //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                                        : ext;
                                                        debugPrint("ext " + ext + " " + ext1 + " " + metaExt + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                                                        + metaExt1);
                                                        if (endsWithIgnoreCase(name, ext1))
                                                        {
                                                            int p = name.length() - ext1.length();
                                                            dumpFile = dump;
                                                            metaFile = new File(dump.getParentFile(), name.substring(0,
                                                                            p)
                                                                            + metaExt1);
                                                            try
                                                            {
                                                                image = fact.getImage(dumpFile, metaFile);
                                                                return image;
                                                            }
                                                            catch (FileNotFoundException e)
                                                            {
                                                                // Ignore for
                                                                // the moment -
                                                                // perhaps both
                                                                // files are not
                                                                // available
                                                                checkIfDiskFull(dumpFile, metaFile, e, format);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            else
                                            {
                                                for (String metaExt : actualMetaExts)
                                                {
                                                    for (String ext : possibleExts)
                                                    {
                                                        String metaExt1 = ext.equals("") && !metaExt.equals("") ? "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                                                        + metaExt : metaExt;
                                                        String ext1 = metaExt.equals("") && !ext.equals("") ? "." + ext //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                                        : ext;
                                                        debugPrint("meta " + ext + " " + ext1 + " " + metaExt + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                                                        + metaExt1);
                                                        if (endsWithIgnoreCase(name, metaExt1))
                                                        {
                                                            int p = name.length() - metaExt1.length();
                                                            dumpFile = new File(dump.getParentFile(), name.substring(0,
                                                                            p)
                                                                            + ext1);
                                                            metaFile = dump;
                                                            try
                                                            {
                                                                image = fact.getImage(dumpFile, metaFile);
                                                                return image;
                                                            }
                                                            catch (FileNotFoundException e)
                                                            {
                                                                // Ignore for
                                                                // the moment -
                                                                // perhaps both
                                                                // files are not
                                                                // available
                                                                checkIfDiskFull(dumpFile, metaFile, e, format);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else if (cext != null)
                                    {
                                        boolean foundext[] = new boolean[1];
                                        String actualExts[] = getActualExts(cext, name, ct0, cts, cts2, foundext);
                                        for (String ext : actualExts)
                                        {
                                            if (endsWithIgnoreCase(name, ext))
                                            {
                                                dumpFile = dump;
                                                metaFile = null;
                                                try
                                                {
                                                    image = fact.getImage(dumpFile);
                                                    return image;
                                                }
                                                catch (RuntimeException e)
                                                {
                                                    // Javacore currently throws
                                                    // IndexOutOfBoundsException
                                                    // for bad dumps
                                                    // Ignore for the moment,
                                                    // will occur again
                                                }
                                                catch (FileNotFoundException e)
                                                {
                                                    // Ignore for the moment -
                                                    // perhaps both files are
                                                    // not available
                                                    checkIfDiskFull(dumpFile, metaFile, e, format);
                                                }
                                            }
                                        }
                                    }
                                }
                                dumpFile = dump;
                                metaFile = null;
                                try
                                {
                                    image = fact.getImage(dumpFile);
                                    return image;
                                }
                                catch (RuntimeException e)
                                {
                                    // Javacore currently throws
                                    // IndexOutOfBoundsException for bad dumps
                                    IOException e1 = new IOException(MessageFormat.format(
                                                    Messages.DTFJIndexBuilder_UnableToReadDumpInDTFJFormat, dumpFile,
                                                    format));
                                    e1.initCause(e);
                                    throw e1;
                                }
                            }
                            catch (FileNotFoundException e)
                            {
                                checkIfDiskFull(dumpFile, metaFile, e, format);
                                throw e;
                            }
                            catch (IOException e)
                            {
                                // Could be out of disk space, so give up now
                                // Clear the cache to attempt to free some disk
                                // space
                                clearCachedDumps();
                                IOException e1 = new IOException(MessageFormat.format(
                                                Messages.DTFJIndexBuilder_UnableToReadDumpMetaInDTFJFormat, dumpFile,
                                                metaFile, format));
                                e1.initCause(e);
                                throw e1;
                            }
                            catch (CoreException e)
                            {
                                IOException e1 = new IOException(MessageFormat.format(
                                                Messages.DTFJIndexBuilder_UnableToReadDumpInDTFJFormat, dump, format));
                                e1.initCause(e);
                                throw e1;
                            }
                        }
                    }
                }
            }
        }
        throw new IOException(MessageFormat.format(Messages.DTFJIndexBuilder_UnableToFindDTFJForFormat, format));
    }

    /**
     * Find the valid file extensions for the supplied file, assuming it is of
     * the requested type.
     * 
     * @param cext
     *            Requested type
     * @param name
     *            The file name
     * @param ctdump
     *            The best guess content type for the file
     * @param cts
     *            All content types based on name
     * @param cts2
     *            All content types not based on name
     * @param found
     *            Did the file type match the content-type?
     * @return array of extensions
     */
    private static String[] getActualExts(IContentType cext, String name, IContentType ctdump, IContentType cts[],
                    IContentType cts2[], boolean found[])
    {
        LinkedHashSet<String> exts = new LinkedHashSet<String>();

        debugPrint("Match " + cext); //$NON-NLS-1$

        // Add best guess extensions first
        if (ctdump != null)
        {
            if (ctdump.isKindOf(cext))
            {
                debugPrint("Found default type " + ctdump); //$NON-NLS-1$
                exts.addAll(Arrays.asList(ctdump.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)));
            }
        }

        // Add other extensions
        if (cts.length > 0)
        {
            for (IContentType ct : cts)
            {
                if (ct.isKindOf(cext))
                {
                    debugPrint("Found possible type with file name " + ct); //$NON-NLS-1$
                    exts.addAll(Arrays.asList(ct.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)));
                }
            }
        }

        if (cts.length == 0)
        {
            // No matching types including the file name
            // Try without file names
            debugPrint("No types"); //$NON-NLS-1$

            boolean foundType = false;
            for (IContentType ct : cts2)
            {
                if (ct.isKindOf(cext))
                {
                    debugPrint("Found possible type without file name " + ct); //$NON-NLS-1$
                    foundType = true;
                    exts.addAll(Arrays.asList(ct.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)));
                }
            }
            if (foundType)
            {
                // We did find that this file is of the required type, but the
                // name might be wrong
                // Add the actual file extension, then this can be used later
                int lastDot = name.lastIndexOf('.');
                if (lastDot >= 0)
                {
                    exts.add(name.substring(lastDot + 1));
                }
                else
                {
                    exts.add(""); //$NON-NLS-1$
                }
            }
        }

        if (exts.isEmpty())
        {
            found[0] = false;
            exts.addAll(Arrays.asList(getPossibleExts(cext)));
        }
        else
        {
            found[0] = true;
        }

        debugPrint("All exts " + exts); //$NON-NLS-1$
        return exts.toArray(new String[exts.size()]);
    }

    /**
     * Get all the possible file extensions for a particular file type. Check
     * all the subtypes too.
     * 
     * @param cext
     * @return possible extensions
     */
    private static String[] getPossibleExts(IContentType cext)
    {
        LinkedHashSet<String> exts = new LinkedHashSet<String>();

        exts.addAll(Arrays.asList(cext.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)));

        for (IContentType ct : Platform.getContentTypeManager().getAllContentTypes())
        {
            if (ct.isKindOf(cext))
            {
                exts.addAll(Arrays.asList(ct.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)));
            }
        }

        return exts.toArray(new String[exts.size()]);
    }

    /**
     * See if one file name ends with an extension (ignoring case).
     * 
     * @param s1
     * @param s2
     * @return
     */
    private static boolean endsWithIgnoreCase(String s1, String s2)
    {
        int start = s1.length() - s2.length();
        return start >= 0 && s2.compareToIgnoreCase(s1.substring(start)) == 0;
    }

    /**
     * Try to spot Out of disk space errors
     * 
     * @param dumpFile
     * @param metaFile
     * @param e
     * @param format
     * @throws IOException
     */
    private static void checkIfDiskFull(File dumpFile, File metaFile, FileNotFoundException e, Serializable format)
                    throws IOException
    {
        if (e.getMessage().contains("Unable to write")) //$NON-NLS-1$
        {
            // Could be out of disk space, so give up now
            // Clear the cache to attempt to free some disk space
            clearCachedDumps();
            IOException e1 = new IOException(MessageFormat.format(
                            Messages.DTFJIndexBuilder_UnableToReadDumpMetaInDTFJFormat, dumpFile, metaFile, format));
            e1.initCause(e);
            throw e1;
        }
    }

    /**
     * To print out debugging messages
     * 
     * @param msg
     */
    private static void debugPrint(String msg)
    {
        if (debugInfo)
            System.out.println(msg);
    }

}