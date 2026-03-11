package ch.epfl.biop.wrappers.spotiflow;

import ch.epfl.biop.wrappers.ExecutePythonInConda;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class DefaultSpotiflowTask extends SpotiflowTask {

    @Override
    public void run() throws Exception {

        try (InputStream is = getClass().getResourceAsStream("/ch/epfl/biop/wrappers/spotiflow/spotiflow.toml")) {
            String content = IOUtils.toString(is, StandardCharsets.UTF_8);
            System.out.println(content);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String envPath = settings.envPath;
        String envType = settings.envType;

        ArrayList<String> arguments = new ArrayList<>();

        arguments.add("spotiflow-predict");
        arguments.add(settings.datasetDir);

        arguments.add("-o");
        arguments.add(settings.datasetDir);

        //arguments.add("--verbose");//we default the verbose now that logger is working

        if (!settings.additional_flags.trim().isEmpty()) {
            String[] flagsList = settings.additional_flags.split(",");
            for (String s : flagsList) {
                if (!s.trim().isEmpty()) {
                    arguments.add(s.trim());
                }
            }
        }

        ExecutePythonInConda.execute(envPath, envType , false ,arguments, null);
    }
}
