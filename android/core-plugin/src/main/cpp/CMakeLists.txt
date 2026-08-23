cmake_minimum_required(VERSION 3.22.1)

project("execbridge")

add_library(execbridge SHARED exec_bridge.c)

target_link_libraries(execbridge android dl log)
