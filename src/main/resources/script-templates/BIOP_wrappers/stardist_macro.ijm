#@File(style="directory" , label="Select conda environment") conda_env_path
#@File(style="directory" , label="Select StarDist model") model_path
run("Blobs (25K)"); // uncomment to test

run("StarDist2D...", "env_path="+conda_env_path+" env_type=conda model_path="+model_path);
