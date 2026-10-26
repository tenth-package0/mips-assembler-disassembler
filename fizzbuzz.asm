# Extracted from the supplied Assembler Java DEMO.
# MARS: delayed branching disabled; prints 1 through 100.
.data
fizz: .asciiz "Fizz"
buzz: .asciiz "Buzz"
.text
.globl main
main:
    li $t1, 1
    li $t2, 101
Loop:
    jal PrintItem
    jal PrintNewLine
    addi $t1, $t1, 1
    bne $t1, $t2, Loop
    j Exit
PrintItem:
    addiu $sp, $sp, -8
    sw $ra, 4($sp)
    rem $t3, $t1, 3
    rem $t4, $t1, 5
    bne $t3, $zero, CheckBuzz
    jal PrintFizz
CheckBuzz:
    bne $t4, $zero, CheckNumber
    jal PrintBuzz
CheckNumber:
    beq $t3, $zero, PrintItemDone
    beq $t4, $zero, PrintItemDone
    jal PrintInt
PrintItemDone:
    lw $ra, 4($sp)
    addiu $sp, $sp, 8
    jr $ra
PrintFizz:
    li $v0, 4
    la $a0, fizz
    syscall
    jr $ra
PrintBuzz:
    li $v0, 4
    la $a0, buzz
    syscall
    jr $ra
PrintInt:
    li $v0, 1
    move $a0, $t1
    syscall
    jr $ra
PrintNewLine:
    li $v0, 11
    li $a0, '\n'
    syscall
    jr $ra
Exit:
    li $v0, 10
    syscall
