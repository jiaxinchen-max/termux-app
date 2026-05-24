cmake_minimum_required(VERSION 3.22.1)

project("termux-render")

include(GNUInstallDirs)

set(TERMUX_RENDER_ASSETS_INSTALL_DIR "${CMAKE_INSTALL_DATADIR}/termux-render/assets")
set(TERMUX_RENDER_ASSETS_SOURCE_DIR "${CMAKE_SOURCE_DIR}/assets")
set(INSTALL_ASSETS "default" CACHE STRING "Assets to install: default or all")
set_property(CACHE INSTALL_ASSETS PROPERTY STRINGS default all)
string(TOLOWER "${INSTALL_ASSETS}" TERMUX_RENDER_INSTALL_ASSETS)
if(NOT TERMUX_RENDER_INSTALL_ASSETS STREQUAL "default" AND
        NOT TERMUX_RENDER_INSTALL_ASSETS STREQUAL "all")
        message(FATAL_ERROR "INSTALL_ASSETS must be 'default' or 'all'")
endif()

set(TERMUX_RENDER_ASSETS_DIR "" CACHE PATH "Runtime path for termux-render assets")
if(NOT TERMUX_RENDER_ASSETS_DIR)
        set(TERMUX_RENDER_ASSETS_DIR "${CMAKE_INSTALL_FULL_DATADIR}/termux-render/assets")
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        tlog.c
        buffer.c
        render.c)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        dl log m
)
install(TARGETS ${CMAKE_PROJECT_NAME}
        LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})
install(FILES
        ${CMAKE_CURRENT_LIST_DIR}/include/buffer.h
        ${CMAKE_CURRENT_LIST_DIR}/include/render.h
        ${CMAKE_CURRENT_LIST_DIR}/include/tlog.h
        DESTINATION include/termux/render
)

function(install_termux_render_asset_directory asset_dir)
        install(DIRECTORY
                "${TERMUX_RENDER_ASSETS_SOURCE_DIR}/${asset_dir}"
                DESTINATION "${TERMUX_RENDER_ASSETS_INSTALL_DIR}"
        )
endfunction()

if(TERMUX_RENDER_INSTALL_ASSETS STREQUAL "all")
        install(DIRECTORY
                "${TERMUX_RENDER_ASSETS_SOURCE_DIR}/"
                DESTINATION "${TERMUX_RENDER_ASSETS_INSTALL_DIR}"
        )
else()
        install_termux_render_asset_directory(animation_wayland)
        install_termux_render_asset_directory(animation_x11)
endif()

add_executable(termux-render-sample main.c)
target_compile_definitions(termux-render-sample PRIVATE
        TERMUX_RENDER_ASSETS_DIR="${TERMUX_RENDER_ASSETS_DIR}")
target_link_libraries(termux-render-sample
        ${CMAKE_PROJECT_NAME}
        )
set_target_properties(termux-render-sample PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")
install(TARGETS termux-render-sample
        RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})

add_executable(termux-render-sample-cpp main.cpp)
target_compile_definitions(termux-render-sample-cpp PRIVATE
        TERMUX_RENDER_ASSETS_DIR="${TERMUX_RENDER_ASSETS_DIR}")
target_link_libraries(termux-render-sample-cpp
        ${CMAKE_PROJECT_NAME}
        )
set_target_properties(termux-render-sample-cpp PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")
install(TARGETS termux-render-sample-cpp
        RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
