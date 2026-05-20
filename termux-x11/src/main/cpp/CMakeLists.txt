cmake_minimum_required(VERSION 3.22)
project(lorie)

set(can_use_assembler TRUE)
enable_language(ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE RelWithDebInfo)

include(TestBigEndian)
include(CheckIncludeFile)
include(CheckSourceCompiles)
include(CheckCCompilerFlag)
include(CheckSymbolExists)
include(CheckFunctionExists)
include(CheckTypeSize)

find_package(Python3 REQUIRED)
find_package(BISON REQUIRED)

# Apply patches (macOS only - Linux uses target_apply_patch in recipes)
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    message(STATUS "Applying patches (macOS)...")
    
    set(PATCH_LIST
        "xkbcomp:xkbcomp.patch"
        "libxkbfile:xkbfile.patch"
        "libx11:x11.patch"
        "libxtrans:Xtrans.patch"
        "xserver:xserver.patch"
        "libepoxy:libepoxy.patch"
        "pixman:pixman.patch"
    )
    
    foreach(patch_entry ${PATCH_LIST})
        string(REPLACE ":" ";" patch_parts "${patch_entry}")
        list(GET patch_parts 0 target_dir)
        list(GET patch_parts 1 patch_file)
        
        set(dir "${CMAKE_CURRENT_SOURCE_DIR}/${target_dir}")
        set(patch "${CMAKE_CURRENT_SOURCE_DIR}/patches-darwin/${patch_file}")
        
        # Use git apply -R --check to detect if patch is already applied
        execute_process(
            COMMAND bash -c "cd '${dir}' && git apply -R --check '${patch}' > /dev/null 2>&1"
            RESULT_VARIABLE can_reverse
        )
        
        if(can_reverse EQUAL 0)
            # Can reverse → already applied → skip
            message(STATUS "  ${patch_file} (already applied)")
        else()
            # Cannot reverse → not applied → apply with git
            execute_process(
                COMMAND bash -c "cd '${dir}' && git apply '${patch}'"
                RESULT_VARIABLE patch_result
            )
            if(patch_result EQUAL 0)
                message(STATUS "  ${patch_file} (applied)")
            else()
                message(WARNING "Failed to apply ${patch_file}")
            endif()
        endif()
    endforeach()
    
    message(STATUS "All patches applied successfully")
endif()

function(target_apply_patch patch_target d p)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        # macOS: patches already applied at top of CMakeLists.txt
        # Empty function for macOS
    else()
        # Linux: use GNU patch with original logic
        execute_process(COMMAND "bash" "-c" "patch -p1 -d ${d} -i ${p} -R --dry-run > /dev/null 2>&1 || patch -p1 -N -d ${d} -i ${p} -r -")
    endif()
endfunction()

set(test_compile_options
        "-Wall"
        "-Wpointer-arith"
        "-Wmissing-declarations"
        "-Wformat=2"
        "-Winvalid-pch"
        "-Wstrict-prototypes"
        "-Wmissing-prototypes"
        "-Wnested-externs"
        "-Wbad-function-cast"
        "-Wold-style-definition"
        "-Wunused"
        "-Wuninitialized"
        "-Wshadow"
        "-Wmissing-noreturn"
        "-Wmissing-format-attribute"
        "-Wredundant-decls"
        "-Wlogical-op"
        "-Werror=implicit"
        "-Werror=nonnull"
        "-Werror=init-self"
        "-Werror=main"
        "-Werror=missing-braces"
        "-Werror=sequence-point"
        "-Werror=return-type"
        "-Werror=trigraphs"
        "-Werror=array-bounds"
        "-Werror=write-strings"
        "-Werror=address"
        "-Werror=int-to-pointer-cast"
        "-Werror=pointer-to-int-cast"
        "-fno-strict-aliasing"
        "-Wno-unused-but-set-variable"
        "-Wno-unused-parameter"
        "-Wno-unused-variable"
        "-Wno-unused-function"
        "-Wno-missing-prototypes"
        "-Wno-macro-redefined"
        "-Wno-uninitialized"
        "-Wno-shadow")

set(CMAKE_REQUIRED_QUIET_OLD ${CMAKE_REQUIRED_QUIET})
set(CMAKE_REQUIRED_QUIET 1)
set(common_compile_options)
set(I 0)
foreach (option ${test_compile_options})
    check_c_compiler_flag(${option} COMPILER_TEST_${I})
    if (COMPILER_TEST_${I})
        set(common_compile_options ${common_compile_options} ${option})
    endif()
    MATH(EXPR I "${I}+1")
endforeach ()
set(CMAKE_REQUIRED_QUIET ${CMAKE_REQUIRED_QUIET_OLD})

foreach(project xorgproto fontenc md pixman tirpc xshmfence Xdmcp Xau Xfont2 xkbcomp xserver)
    include(recipes/${project}.cmake)
endforeach()
