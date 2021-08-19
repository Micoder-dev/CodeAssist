package com.tyron.code.parser;
import java.util.HashMap;
import java.io.File;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.Enumeration;
import java.io.IOException;
import android.widget.Toast;
import com.tyron.code.MainActivity;
import com.tyron.code.ApplicationLoader;
import java.util.List;
import java.util.Set;
import com.tyron.code.util.Decompress;
import java.util.TreeMap;
import java.time.Instant;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.StringReader;
import com.tyron.code.util.StringSearch;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.FileWriter;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Class responsible for caching java files for fast
 * lookup later whenever we need to.
 */
public class FileManager {

    private static FileManager INSTANCE = null;
    private final ExecutorService service = Executors.newFixedThreadPool(4);
    
    public static FileManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FileManager();
        }
        return INSTANCE;
    }

    FileManager() {

    }

    // Map of compiled (.class) files with their fully qualified name as key
    private final Map<String, File> files = new HashMap<>();

    /** javaSources[file] is the javaSources time of a .java source file. */
    private final TreeMap<File, Info> javaSources = new TreeMap<>();

    private static class Info {
        final Instant modified;
        final String packageName;

        Info(Instant modified, String packageName) {
            this.modified = modified;
            this.packageName = packageName;
        }
    }

    public Set<String> all() {
        return files.keySet();
    }

    void putJar(File file) throws IOException {
        JarFile jar = new JarFile(file);
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();

            if (!entry.getName().endsWith(".class")) {
                continue;
            }

            // We only want top level classes, if it contains $ then 
            // its an inner class, we ignore it
            if (entry.getName().contains("$")) {
                continue;
            }

            String packageName = entry.getName().replace("/", ".")
                .substring(0, entry.getName().length() - ".class".length());                   


            files.put(packageName, file);
        }
    }
    
    public void save(final File file, final String contents) {
        service.submit(new Runnable() {
            @Override
            public void run() {
                writeFile(file, contents);
            }
        });
    }
    
    public static String readFile(File file) {
        createNewFile(file);

        StringBuilder sb = new StringBuilder();
        FileReader fr = null;
        try {
            fr = new FileReader(file);

            char[] buff = new char[1024];
            int length = 0;

            while ((length = fr.read(buff)) > 0) {
                sb.append(new String(buff, 0, length));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fr != null) {
                try {
                    fr.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return sb.toString();
    }
    
    private static void createNewFile(File file) {
        String path = file.getAbsolutePath();
        int lastSep = path.lastIndexOf(File.separator);
        if (lastSep > 0) {
            String dirPath = path.substring(0, lastSep);
            makeDir(new File(dirPath));
        }
        try {
            if (!file.exists())
                file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
    
    
    public static void makeDir(File file) {
        if (!file.exists()) {
            file.mkdirs();
        }
	}

    public static void writeFile(File file, String str) {
        createNewFile(file);
        FileWriter fileWriter = null;

        try {
            fileWriter = new FileWriter(file, false);
            fileWriter.write(str);
            fileWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fileWriter != null)
                    fileWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
	    }
    }

    public static BufferedReader bufferedReader(File file) {
        try {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        } catch (FileNotFoundException e) {
            return new BufferedReader(new StringReader(""));
        }
    }

    public static BufferedReader lines(File file) {
        return bufferedReader(file);
    }

    public File getAndroidJar() {
        File jarFile = new File(ApplicationLoader.applicationContext
                                .getFilesDir(), "rt.jar");
        if (jarFile.exists()) {
            return jarFile;
        } else {
            Decompress.unzipFromAssets(ApplicationLoader.applicationContext,
                                       "rt.zip",
                                       jarFile.getParentFile().getAbsolutePath());          
            return jarFile;
        }
    }
}
