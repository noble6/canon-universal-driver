/* Dump the obfuscated CNIJ blocks (device ID, IVEC capability, maintenance
 * capability) from a Canon cnijfilter2 PPD, decoded by Canon's own library. */
#include <stdio.h>
#include <stdint.h>
#include <dlfcn.h>

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: capdump file.ppd\n"); return 2; }
    void *h = dlopen("libcnbpcnclapicom2.so", RTLD_LAZY);
    if (!h) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    int (*get)(const char *, const char *, int *, uint8_t **) = dlsym(h, "CNCL_GetStringWithTagFromFile");
    const char *tags[] = { "CNIJ-DEVCE-INFO", "CNIJ-IVEC-CAPABILITY", "CNIJ-IVEC-CAPABILITY-MAINTENANCE" };
    for (int t = 0; t < 3; t++) {
        uint8_t *buf = NULL;
        int n = get(argv[1], tags[t], (int *)1, &buf);
        printf("=====%s len=%d\n", tags[t], n);
        if (n > 0) { fwrite(buf, 1, n, stdout); printf("\n"); }
    }
    return 0;
}
