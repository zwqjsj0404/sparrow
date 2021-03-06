""" This file runs multiple simulations to measure the effect of probing. """
import simulation
import subprocess

import stats

class EffectOfProbes:
    def __init__(self, file_prefix, remove_delay=False):
        self.probes_ratio_values = [-1.0, 1.0, 1.2, 1.5]
        self.num_servers = 5000
        self.cores_per_server = 4
        self.total_time = 2e4
        
        # Prefix used for all files, including plotting and aggregated data
        # files.  Note that a longer prefix is used for the results from each
        # inidividual trial.
        self.file_prefix = file_prefix
        self.remove_delay = remove_delay

    def get_prefix(self, trial_number, probes_ratio):
        extra = ""
        if trial_number >= 0:
            extra = "_%d" % trial_number
        return "%s%s_%s" % (self.file_prefix, extra, probes_ratio)
        
    def run_single(self, trial_number):
        """ Using -1 as the trial number indicates that this is for a 
        single run, so the trial number won't be included in the filename. """
        avg_num_tasks = 200.
        task_length = 100
        for probes_ratio in self.probes_ratio_values:
            network_delay = 2
            if probes_ratio == -1:
                network_delay = 0
            first = True
            # Number of different utilization values.
            utilization_granularity = 10
            file_prefix = self.get_prefix(trial_number, probes_ratio)
            for i in range(1, utilization_granularity + 1):
                arrival_delay = (task_length * avg_num_tasks *
                                 utilization_granularity /
                                 (self.num_servers * self.cores_per_server *
                                  i))
                simulation.main(["job_arrival_delay=%f" % arrival_delay,
                                 "num_users=1",
                                 "network_delay=%d" % network_delay,
                                 "probes_ratio=%f" % probes_ratio,
                                 "task_length_distribution=constant",
                                 "num_tasks=%d" % avg_num_tasks,
                                 "task_length=%d" % task_length,
                                 "task_distribution=constant",
                                 "load_metric=total",
                                 "cores_per_server=%d" % self.cores_per_server,
                                 "file_prefix=%s" % file_prefix,
                                 "num_servers=%d" % self.num_servers,
                                 "total_time=%d" % self.total_time,
                                 "first_time=%s" % first])
                first = False
        
    def graph_single(self):
        """ Graphs the result of a single experiment. """
        file_prefix = "%s_single" %  self.file_prefix
        filename = "plot_%s.gp" % file_prefix
        gnuplot_file = open(filename, 'w')
        gnuplot_file.write("set terminal postscript color 'Helvetica' 14\n")
        gnuplot_file.write("set size .5, .4\n")
        gnuplot_file.write("set output 'graphs/%s.ps'\n" % file_prefix)
        gnuplot_file.write("set xlabel 'Utilization'\n")
        gnuplot_file.write("set ylabel 'Job Response Time (ms)'\n")
        gnuplot_file.write("set yrange [0:500]\n")
        gnuplot_file.write("set grid ytics\n")
        gnuplot_file.write("set key at 1,700 horizontal\n")
        gnuplot_file.write("plot ")
        
        for i, probes_ratio in enumerate(self.probes_ratio_values):
            results_filename = ("raw_results/%s_response_time" %
                                self.get_prefix(-1, probes_ratio))
            if i > 0:
                gnuplot_file.write(', \\\n')
            gnuplot_file.write(("'%s' using 3:7 title '%s' lt %d lw 4"
                                " with l,\\\n") %
                               (results_filename, probes_ratio, i))
            gnuplot_file.write(("'%s' using 3:7:5 notitle lt %d lw 1 "
                                "with errorbars") % (results_filename, i))
            
        subprocess.call(["gnuplot", filename])
        
    def run(self, num_trials):
        """ Runs the given number of trials.
        
        If num_trials is 1, runs a single trial and graphs the result.
        Otherwise, graphs averaged results over all trials.
        """
        if num_trials == 1:
            self.run_single(-1)
            self.graph_single()
            return

        for trial in range(num_trials):
            print "********Running Trial %s**********" % trial
            self.run_single(trial)
            
        filename = "plot_%s.gp" % self.file_prefix
        gnuplot_file = open(filename, 'w')
        gnuplot_file.write("set terminal postscript color 'Helvetica' 14\n")
        #gnuplot_file.write("set size .5, .5\n")
        gnuplot_file.write("set output 'graphs/%s.ps'\n" % self.file_prefix)
        gnuplot_file.write("set xlabel 'Utilization'\n")
        gnuplot_file.write("set ylabel 'Response Time (ms)'\n")
        gnuplot_file.write("set yrange [0:700]\n")
        gnuplot_file.write("set grid ytics\n")
        #gnuplot_file.write("set xtics 0.25\n")
        extra = ""
        gnuplot_file.write("set title 'Effect of Load Probing on Response "
                           "Time%s'\n" % extra)
        #gnuplot_file.write("set key font 'Helvetica,10' left width -5"
        #                   "title 'Probes:Tasks' samplen 2\n")
        gnuplot_file.write("set key left\n")
        gnuplot_file.write("plot ")
        
        for i, probes_ratio in enumerate(self.probes_ratio_values):
            # Aggregate results and write to a file.
            # Map of utilization to response times for that utilization.
            results = {}
            for trial in range(num_trials):
                results_filename = ("raw_results/%s_response_time" %
                                    self.get_prefix(trial, probes_ratio))
                results_file = open(results_filename, "r")
                index = 0
                for line in results_file:
                    values = line.split("\t")
                    if values[0] == "n":
                        continue
                    # Use median response time.
                    normalized_response_time = float(values[6])
                    if self.remove_delay:
                        normalized_response_time -= 3*float(values[9])
                    utilization = float(values[2])
                    if utilization not in results:
                        results[utilization] = []
                    results[utilization].append(normalized_response_time)
                    
            agg_output_filename = ("raw_results/agg_%s_%f" %
                                   (self.file_prefix, probes_ratio))
            agg_output_file = open(agg_output_filename, "w")
            agg_output_file.write("Utilization\tResponseTime\tStdDev\n")
            for utilization in sorted(results.keys()):
                avg_response_time = stats.lmean(results[utilization])
                std_dev = stats.lstdev(results[utilization])
                agg_output_file.write("%f\t%f\t%f\n" %
                                      (utilization, avg_response_time, std_dev))
                
            # Plot aggregated results.
            if i > 0:
                gnuplot_file.write(', \\\n')
            title = "Probes/Tasks = %s" % probes_ratio
            if probes_ratio == -1:
                title = "Ideal"
            gnuplot_file.write(("'%s' using 1:2 title '%s' lc %d lw 4 with l,"
                                "\\\n") %
                               (agg_output_filename, title, i))
            gnuplot_file.write(("'%s' using 1:2:3 notitle lt %d lw 4 with "
                                "errorbars") % (agg_output_filename, i))
            
        subprocess.call(["gnuplot", filename])

def main():
    experiment = EffectOfProbes("medium_multicore")
    experiment.run(1)

if __name__ == '__main__':
    main()