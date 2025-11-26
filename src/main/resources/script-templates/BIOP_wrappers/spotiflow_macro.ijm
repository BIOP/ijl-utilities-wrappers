#@File(style="directory" , label="Select conda environment") conda_env_path
close("*");
roiManager("reset");

run("M51 Galaxy (16-bits)");// uncomment to test

run("Spotiflow ...", "env_path="+conda_env_path+" env_type=conda additional_flags= ");
roiManager("Show All");