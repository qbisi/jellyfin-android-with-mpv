cmake_minimum_required(VERSION 3.22.1)

project(mpv_log_control LANGUAGES CXX)

add_library(mpv_log_control SHARED mpv_log_control.cpp)

target_compile_features(mpv_log_control PRIVATE cxx_std_17)
target_link_libraries(mpv_log_control PRIVATE dl)
