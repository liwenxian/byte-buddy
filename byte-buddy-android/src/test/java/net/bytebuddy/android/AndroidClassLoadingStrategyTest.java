package net.bytebuddy.android;

import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.file.DexFile;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.dynamic.ClassLoadingStrategy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.instrumentation.FixedValue;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.test.utility.MockitoRule;
import net.bytebuddy.test.utility.ObjectPropertyAssertion;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mock;

import java.io.File;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class AndroidClassLoadingStrategyTest {

    private static final String FOO = "bar.foo", BAR = "foo.bar", TEMP = "tmp", TO_STRING = "toString";

    private static final byte[] QUX = new byte[]{1, 2, 3}, BAZ = new byte[]{4, 5, 6};

    @Rule
    public TestRule dexCompilerRule = new MockitoRule(this);

    private File directory;

    @Mock
    private TypeDescription first, second;

    @Before
    public void setUp() throws Exception {
        directory = File.createTempFile(FOO, TEMP);
        assertThat(directory.delete(), is(true));
        directory = new File(directory.getParentFile(), UUID.randomUUID().toString());
        assertThat(directory.mkdir(), is(true));
        when(first.getName()).thenReturn(FOO);
        when(second.getName()).thenReturn(BAR);
    }

    @After
    public void tearDown() throws Exception {
        assertThat(directory.delete(), is(true));
    }

    @Test
    public void testProcessing() throws Exception {
        AndroidClassLoadingStrategy.DexProcessor dexProcessor = mock(AndroidClassLoadingStrategy.DexProcessor.class);
        ClassLoader classLoader = mock(ClassLoader.class);
        doReturn(Object.class).when(classLoader).loadClass(FOO);
        doReturn(Void.class).when(classLoader).loadClass(BAR);
        when(dexProcessor.makeClassLoader(any(File.class), eq(directory), any(ClassLoader.class))).thenReturn(classLoader);
        AndroidClassLoadingStrategy.DexProcessor.Conversion conversion = mock(AndroidClassLoadingStrategy.DexProcessor.Conversion.class);
        when(dexProcessor.create()).thenReturn(conversion);
        ClassLoadingStrategy classLoadingStrategy = new AndroidClassLoadingStrategy(directory, dexProcessor);
        Map<TypeDescription, byte[]> unloaded = new HashMap<TypeDescription, byte[]>();
        unloaded.put(first, QUX);
        unloaded.put(second, BAZ);
        ClassLoader parentClassLoader = mock(ClassLoader.class);
        Map<TypeDescription, Class<?>> loaded = classLoadingStrategy.load(parentClassLoader, unloaded);
        assertThat(loaded.size(), is(2));
        assertEquals(Object.class, loaded.get(first));
        assertEquals(Void.class, loaded.get(second));
        verify(dexProcessor).create();
        verify(dexProcessor).makeClassLoader(any(File.class), eq(directory), eq(parentClassLoader));
        verifyNoMoreInteractions(dexProcessor);
        verify(conversion).register(FOO, QUX);
        verify(conversion).register(BAR, BAZ);
        verify(conversion).drainTo(any(OutputStream.class));
        verifyNoMoreInteractions(conversion);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAndroidClassLoaderRequiresDirectory() throws Exception {
        new AndroidClassLoadingStrategy(mock(File.class), mock(AndroidClassLoadingStrategy.DexProcessor.class));
    }

    @Test
    public void testStubbedClassLoading() throws Exception {
        DynamicType dynamicType = new ByteBuddy(ClassFileVersion.JAVA_V6).subclass(Object.class)
                .method(named(TO_STRING)).intercept(FixedValue.value(FOO))
                .make();
        ClassLoader classLoader = mock(ClassLoader.class);
        doReturn(Void.class).when(classLoader).loadClass(any(String.class));
        ClassLoadingStrategy classLoadingStrategy = new AndroidClassLoadingStrategy(directory, new StubbedClassLoaderDexCompilation(classLoader));
        Map<TypeDescription, Class<?>> map = classLoadingStrategy.load(getClass().getClassLoader(), dynamicType.getAllTypes());
        assertThat(map.size(), is(1));
        assertEquals(Void.class, map.get(dynamicType.getTypeDescription()));
    }

    @Test
    public void testObjectProperties() throws Exception {
        ObjectPropertyAssertion.of(AndroidClassLoadingStrategy.class)
                .apply(new AndroidClassLoadingStrategy(directory, mock(AndroidClassLoadingStrategy.DexProcessor.class)));
        ObjectPropertyAssertion.of(AndroidClassLoadingStrategy.DexProcessor.ForSdkCompiler.class).apply();
        ObjectPropertyAssertion.of(AndroidClassLoadingStrategy.DexProcessor.ForSdkCompiler.Conversion.class).create(new ObjectPropertyAssertion.Creator<DexFile>() {
            @Override
            public DexFile create() {
                return new DexFile(new DexOptions());
            }
        }).apply();
    }

    private static class StubbedClassLoaderDexCompilation implements AndroidClassLoadingStrategy.DexProcessor {

        private final ClassLoader classLoader;

        private StubbedClassLoaderDexCompilation(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        public Conversion create() {
            return new AndroidClassLoadingStrategy.DexProcessor.ForSdkCompiler(new DexOptions(), new CfOptions()).create();
        }

        @Override
        public ClassLoader makeClassLoader(File zipFile, File privateDirectory, ClassLoader parentClassLoader) {
            return classLoader;
        }
    }
}
