#include <check.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

// Function prototype for the Kotlin function we're testing
// This assumes the Kotlin/Native C bridge exports this function
extern char** enableCommands(const char** services, int services_count, const char* pacUrl, int* result_count);

START_TEST(test_shell_injection_prevention)
{
    // Invariant: Shell commands never include unsanitized user input
    const char *payloads[] = {
        "http://example.com/proxy.pac",  // Valid input
        "http://example.com/proxy.pac; rm -rf /",  // Shell command injection
        "http://example.com/proxy.pac && cat /etc/passwd",  // Command chaining
        "http://example.com/proxy.pac`id`",  // Backtick execution
        "http://example.com/proxy.pac$(whoami)"  // Subshell execution
    };
    
    const char* services[] = {"Wi-Fi", "Ethernet"};
    int services_count = 2;
    
    int num_payloads = sizeof(payloads) / sizeof(payloads[0]);

    for (int i = 0; i < num_payloads; i++) {
        int result_count = 0;
        char** commands = enableCommands(services, services_count, payloads[i], &result_count);
        
        // Check that commands were generated
        ck_assert_ptr_nonnull(commands);
        ck_assert_int_gt(result_count, 0);
        
        // Verify each command doesn't contain unsanitized payload
        for (int j = 0; j < result_count; j++) {
            // The pacUrl should appear exactly as passed, not interpreted
            // Check that the command array contains the pacUrl as a separate argument
            int found_pacurl = 0;
            for (int k = 0; k < result_count; k++) {
                if (commands[k] != NULL && strstr(commands[k], payloads[i]) != NULL) {
                    found_pacurl = 1;
                    // If found, verify it's not part of a larger shell command
                    ck_assert_str_eq(commands[k], payloads[i]);
                }
            }
            ck_assert_int_eq(found_pacurl, 1);
        }
        
        // Cleanup
        for (int j = 0; j < result_count; j++) {
            free(commands[j]);
        }
        free(commands);
    }
}
END_TEST

Suite *security_suite(void)
{
    Suite *s;
    TCase *tc_core;

    s = suite_create("Security");
    tc_core = tcase_create("Core");

    tcase_add_test(tc_core, test_shell_injection_prevention);
    suite_add_tcase(s, tc_core);

    return s;
}

int main(void)
{
    int number_failed;
    Suite *s;
    SRunner *sr;

    s = security_suite();
    sr = srunner_create(s);

    srunner_run_all(sr, CK_NORMAL);
    number_failed = srunner_ntests_failed(sr);
    srunner_free(sr);

    return (number_failed == 0) ? EXIT_SUCCESS : EXIT_FAILURE;
}