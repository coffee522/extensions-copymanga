import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

class VerifyExtensionJvm {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        String fingerprint = args[1];
        try (JarFile archive = new JarFile(path.toFile(), true);
                URLClassLoader loader = new URLClassLoader(new URL[] {path.toUri().toURL()})) {
            String entryName = entryClassName(archive, fingerprint);
            Class<?> entry = linkClass(entryName, loader, fingerprint);
            Class<?> factory = loader.loadClass("eu.kanade.tachiyomi.source.SourceFactory");
            Class<?> source = loader.loadClass("eu.kanade.tachiyomi.source.Source");
            if (!factory.isAssignableFrom(entry) && !source.isAssignableFrom(entry)) {
                throw new IllegalStateException(entryName + " does not implement the Mihon entry-point ABI");
            }
            entry.getConstructor();
            int count = 0;
            for (JarEntry item : archive.stream().filter(item -> isClass(item.getName())).toList()) {
                String name = item.getName().substring(0, item.getName().length() - 6).replace('/', '.');
                linkClass(name, loader, fingerprint);
                count++;
            }
            System.out.printf("JVM -Xverify:all: PASS (%s, %d classes linked)%n", entryName, count);
        }
    }

    private static boolean isClass(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class");
    }

    private static Class<?> linkClass(String name, URLClassLoader loader, String fingerprint) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        if (type.getClassLoader() != loader) {
            throw new IllegalStateException(name + " was loaded from the host instead of the release JAR");
        }
        // Reflection forces verification and resolves method descriptors without running Android initialization.
        type.getDeclaredConstructors();
        type.getDeclaredMethods();
        verifySigner(type.getProtectionDomain().getCodeSource().getCertificates(), fingerprint, name);
        return type;
    }

    private static String entryClassName(JarFile archive, String fingerprint) throws Exception {
        DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
        parser.setNamespaceAware(true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        JarEntry manifestEntry = archive.getJarEntry("AndroidManifest.xml");
        try (InputStream manifest = archive.getInputStream(manifestEntry)) {
            var root = parser.newDocumentBuilder().parse(manifest).getDocumentElement();
            verifySigner(manifestEntry.getCertificates(), fingerprint, manifestEntry.getName());
            var metadata = root.getElementsByTagName("meta-data");
            for (int i = 0; i < metadata.getLength(); i++) {
                Element element = (Element) metadata.item(i);
                if (element.getAttributeNS(ANDROID, "name").equals("tachiyomi.extension.class")) {
                    String name = element.getAttributeNS(ANDROID, "value");
                    return name.startsWith(".") ? root.getAttribute("package") + name : name;
                }
            }
        }
        throw new IllegalStateException("JAR manifest has no Mihon entry point");
    }

    private static void verifySigner(Certificate[] certificates, String fingerprint, String entry) throws Exception {
        if (fingerprint.isEmpty()) {
            return;
        }
        if (certificates == null || certificates.length != 1) {
            throw new SecurityException("Unexpected signing chain for " + entry);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded());
        if (!HexFormat.of().formatHex(digest).equals(fingerprint)) {
            throw new SecurityException("Unexpected signing certificate for " + entry);
        }
    }
}
