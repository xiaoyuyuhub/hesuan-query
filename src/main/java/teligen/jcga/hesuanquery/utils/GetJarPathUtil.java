package teligen.jcga.hesuanquery.utils;

import org.springframework.boot.system.ApplicationHome;

import java.io.File;

public class GetJarPathUtil {

    static {
        ApplicationHome home = new ApplicationHome(GetJarPathUtil.class);
        File jarFile = home.getSource();

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("window")) {
            JAR_ROOT_PATH = "C:/hesuanqyery/file/";
        } else {
            JAR_ROOT_PATH = jarFile.getParentFile().toString() + "/file/";
        }
    }

    public static final String JAR_ROOT_PATH;
}
