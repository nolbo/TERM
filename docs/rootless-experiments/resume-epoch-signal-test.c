#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#define CHECK(c) do { if (!(c)) { fprintf(stderr,"FAIL %d: %s errno=%d\n",__LINE__,#c,errno); exit(1); } } while(0)
static volatile sig_atomic_t armed, delivered;
static int source, target;
static void replace(int signal) {
    (void)signal;
    if (syscall(SYS_dup3, source, target, 0) != target) _exit(90);
    delivered = 1;
}
void proot_epoch_test_hook(int fd) {
    if (armed && fd == target) { armed = 0; CHECK(raise(SIGUSR1) == 0); }
}
int main(void) {
    char directory[] = "/tmp/epoch-XXXXXX";
    CHECK(mkdtemp(directory)); CHECK(chdir(directory) == 0);
    target = open("linked", O_CREAT|O_RDWR,0600); CHECK(target>=0);
    CHECK(write(target,"old",3)==3); CHECK(link("linked","alias")==0);
    source=open("replacement",O_CREAT|O_RDWR,0600);CHECK(source>=0);
    CHECK(write(source,"replacement data",16)==16);
    struct stat before, expected, actual;
    CHECK(fstat(target,&before)==0 && before.st_nlink==2);
    CHECK(fstat(source,&expected)==0);
    struct sigaction action={.sa_handler=replace};
    CHECK(sigemptyset(&action.sa_mask)==0);CHECK(sigaction(SIGUSR1,&action,NULL)==0);
    armed=1;
    CHECK(fstat(target,&actual)==0);CHECK(delivered && !armed);
    CHECK(actual.st_ino==expected.st_ino && actual.st_size==expected.st_size && actual.st_nlink==expected.st_nlink);
    CHECK(close(target)==0);CHECK(close(source)==0);
    CHECK(unlink("linked")==0);CHECK(unlink("alias")==0);CHECK(unlink("replacement")==0);
    CHECK(chdir("/")==0);CHECK(rmdir(directory)==0);
    puts("PASS epoch: signal replaced an emulated backing FD between raw fstat and pathname check; result retried correctly");
    return 0;
}
