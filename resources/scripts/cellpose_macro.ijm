#@File(style="directory" , label="Select conda environment") conda_env_path
//run("Blobs (25K)"); // uncomment to test
image_title = getTitle();

run("Cellpose ..." ,"imp="+image_title+" conda_env_path="+conda_env_path+" env_type=conda diameter=30 model=cyto3 model_path= ch1=1 ch2=1 additional_flags=--use_gpu" );