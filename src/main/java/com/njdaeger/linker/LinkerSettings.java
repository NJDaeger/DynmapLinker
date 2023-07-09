package com.njdaeger.linker;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class LinkerSettings {

    private final Map<String, Object> data;

    public LinkerSettings() throws IOException, URISyntaxException {
        var url = Linker.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        var folder = new File(url).getParentFile();
        if (!new File(folder + "/settings.yml").exists()) {
            try (var is = Linker.class.getResourceAsStream("/settings.yml")) {
                if (is == null) throw new RuntimeException("Could not find settings.yml in the jar file");
                Files.copy(is, Path.of(folder + "/settings.yml"));
            }
            System.out.println("settings.yml was not found. A default one has been created. Please edit it and restart the program.");
            System.out.println("Exiting.");
            System.exit(0);
        }

        var yml = new Yaml();
        this.data = yml.load(new FileInputStream(folder + "/settings.yml"));
    }

    public Object get(String key) {
        return getValueFromNestedMap(data, key);
    }

    private static Object getValueFromNestedMap(Map<String, Object> map, String key) {
        if (key.isEmpty()) {
            return null;
        }

        int dotIndex = key.indexOf('.');
        if (dotIndex == -1) {
            return map.get(key);
        }

        String currentKey = key.substring(0, dotIndex);
        String remainingKey = key.substring(dotIndex + 1);

        Object value = map.get(currentKey);
        if (value instanceof Map) {
            return getValueFromNestedMap((Map<String, Object>) value, remainingKey);
        }

        return null;
    }

}
